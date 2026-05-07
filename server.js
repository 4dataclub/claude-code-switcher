const express = require('express');
const fs = require('fs');
const path = require('path');
const Docker = require('dockerode');

const app = express();
const PORT = process.env.PORT || 3000;
const CONFIG_PATH = process.env.CLAUDE_CONFIG_PATH || path.join('/root', '.claude', 'settings.json');
const ROUTER_CONFIG_PATH = process.env.ROUTER_CONFIG_PATH || path.join('/root', '.claude', 'router-config.json');
const ROUTER_CONTAINER = process.env.ROUTER_CONTAINER || 'claude-switcher-router-1';
const ROUTER_BASE_URL = process.env.ROUTER_BASE_URL || 'http://router:3456';
// Wenn der Wrapper auf dem Host läuft, muss er den Router unter localhost ansprechen.
const HOST_ROUTER_URL = 'http://localhost:3456';

const docker = new Docker({ socketPath: '/var/run/docker.sock' });

app.use(express.json());
app.use(express.static('public'));

// ─── Config-IO ──────────────────────────────────────────────────────────────

function readConfig() {
  try {
    if (!fs.existsSync(CONFIG_PATH)) return {};
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch {
    return {};
  }
}

function writeConfig(config) {
  const dir = path.dirname(CONFIG_PATH);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
}

function maskKey(key) {
  if (!key || key.length < 8) return '';
  return key.slice(0, 8) + '••••••••••••••••' + key.slice(-4);
}

// ─── Router-Config (claude-code-router) ─────────────────────────────────────

function buildRouterConfig(keys, fallback) {
  // Erzeugt die config.json für claude-code-router.
  // Keys leer = Provider auskommentiert. Router braucht mindestens einen Provider.
  const Providers = [];

  if (keys.google) {
    Providers.push({
      name: 'gemini',
      api_base_url: 'https://generativelanguage.googleapis.com/v1beta/models/',
      api_key: keys.google,
      models: ['gemini-2.5-pro', 'gemini-2.5-flash', 'gemini-2.5-flash-lite'],
      transformer: { use: ['gemini'] },
    });
  }

  if (keys.openrouter) {
    Providers.push({
      name: 'openrouter',
      api_base_url: 'https://openrouter.ai/api/v1/chat/completions',
      api_key: keys.openrouter,
      models: [
        'anthropic/claude-sonnet-4.5',
        'google/gemini-2.5-flash',
        'google/gemini-2.5-pro',
        'meta-llama/llama-3.3-70b-instruct:free',
        'openai/gpt-oss-120b:free',
        'nvidia/nemotron-nano-9b-v2:free',
        'deepseek/deepseek-chat-v3.1',
      ],
      transformer: { use: ['openrouter'] },
    });
  }

  // Default-Route ist der konfigurierte Fallback (oder erster verfügbarer Provider)
  const defaultRoute =
    fallback && fallback.provider && fallback.model
      ? `${fallback.provider},${fallback.model}`
      : Providers[0]
      ? `${Providers[0].name},${Providers[0].models[0]}`
      : '';

  return {
    LOG: true,
    HOST: '0.0.0.0',
    PORT: 3456,
    API_TIMEOUT_MS: 600000,
    Providers,
    Router: {
      default: defaultRoute,
      background: defaultRoute,
      think: defaultRoute,
      longContext: defaultRoute,
    },
  };
}

// UI/Friendly-Name → Router-Internal-Name
const PROVIDER_MAP = { google: 'gemini', openrouter: 'openrouter', anthropic: 'anthropic' };

function writeRouterConfig(config) {
  const switcher = config._switcher || {};
  const route = switcher.activeRoute
    || (switcher.fallback_chain && switcher.fallback_chain[0])
    || switcher.fallback
    || {};
  // Map UI-Provider → Router-Internal
  const mappedRoute = route.provider
    ? { provider: PROVIDER_MAP[route.provider] || route.provider, model: route.model }
    : {};
  const routerCfg = buildRouterConfig(switcher.keys || {}, mappedRoute);
  fs.writeFileSync(ROUTER_CONFIG_PATH, JSON.stringify(routerCfg, null, 2));
}

async function restartRouter() {
  try {
    const container = docker.getContainer(ROUTER_CONTAINER);
    await container.restart({ t: 2 });

    // Warten bis Router-HTTP antwortet (max 30s).
    // TCP-Connect zu socat klappt sofort — aber ccr (3456 intern) ist noch
    // nicht ready. Daher: echter HTTP-GET damit wir wissen dass ccr da ist.
    const http = require('http');
    const isReady = () => new Promise((resolve) => {
      const req = http.get({ host: 'router', port: 3457, path: '/', timeout: 1500 }, (res) => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.once('error', () => resolve(false));
      req.once('timeout', () => { req.destroy(); resolve(false); });
    });
    let firstReadyAt = -1;
    for (let i = 0; i < 30; i++) {
      if (await isReady()) {
        if (firstReadyAt < 0) firstReadyAt = i;
        // Extra-Puffer: 2s nach erstem 200, weil ccr Routes
        // (besonders gemini-pro) länger brauchen bis sie funktional sind
        if (i >= firstReadyAt + 2) {
          return { ok: true, readyAfterMs: i * 1000 };
        }
      } else {
        firstReadyAt = -1; // reset wenn flaky
      }
      await new Promise(r => setTimeout(r, 1000));
    }
    return { ok: false, error: 'Router nicht bereit nach 30s' };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

// ─── SSE für UI-Live-Updates ────────────────────────────────────────────────

const sseClients = new Set();

function broadcast(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    try { res.write(payload); } catch {}
  }
}

app.get('/api/events', (req, res) => {
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write('retry: 3000\n\n');
  sseClients.add(res);
  req.on('close', () => sseClients.delete(res));
});

// ─── Status ─────────────────────────────────────────────────────────────────

function deriveProvider(config) {
  // Source of truth: _switcher.provider. Fallback: ableiten aus env.
  if (config._switcher?.provider) return config._switcher.provider;
  const baseUrl = config.env?.ANTHROPIC_BASE_URL || '';
  if (baseUrl.includes('localhost:3456') || baseUrl.includes('router:3456')) {
    return 'google'; // Default-Annahme wenn Router-URL gesetzt
  }
  if (baseUrl.includes('openrouter.ai')) return 'openrouter';
  return 'anthropic';
}

const DEFAULT_CHAIN = [
  { provider: 'google', model: 'gemini-2.5-pro' },
  { provider: 'google', model: 'gemini-2.5-flash' },
  { provider: 'google', model: 'gemini-2.5-flash-lite' },
];

app.get('/api/status', (req, res) => {
  const config = readConfig();
  const switcher = config._switcher || {};
  const keys = switcher.keys || {};
  const chain = switcher.fallback_chain || DEFAULT_CHAIN;
  const position = switcher.chain_position || 0;

  res.json({
    provider: deriveProvider(config),
    model: config.model || null,
    mode: switcher.mode || 'manual',
    fallback_chain: chain,
    chain_position: position,
    chain_exhausted: position >= chain.length,
    // Backwards-compat: erster Chain-Eintrag = legacy "fallback"
    fallback: chain[0] || { provider: 'google', model: 'gemini-2.5-pro' },
    anthropicKeyMasked: maskKey(keys.anthropic || ''),
    googleKeyMasked: maskKey(keys.google || ''),
    openrouterKeyMasked: maskKey(keys.openrouter || ''),
    hasAnthropicKey: !!keys.anthropic,
    hasGoogleKey: !!keys.google,
    hasOpenRouterKey: !!keys.openrouter,
    lastWarn: switcher.lastWarn || null,
  });
});

// ─── Switch ────────────────────────────────────────────────────────────────

app.post('/api/switch', async (req, res) => {
  const { provider, model, anthropicKey, googleKey, openrouterKey } = req.body;
  if (!provider) return res.status(400).json({ error: 'provider required' });

  const config = readConfig();
  if (!config.env) config.env = {};
  if (!config._switcher) config._switcher = { keys: {} };
  if (!config._switcher.keys) config._switcher.keys = {};

  // Keys aktualisieren (ohne __UNCHANGED__-Sentinel) + Format-Validation
  const KEY_PATTERNS = {
    anthropic:  /^sk-ant-(api03|oat01)-/,
    google:     /^AIza[A-Za-z0-9_-]{30,}$/,
    openrouter: /^sk-or-v1-/,
  };
  for (const [k, v] of [
    ['anthropic', anthropicKey],
    ['google', googleKey],
    ['openrouter', openrouterKey],
  ]) {
    if (v && v !== '__UNCHANGED__') {
      if (!KEY_PATTERNS[k].test(v)) {
        return res.status(400).json({
          error: `${k}-Key hat falsches Format. Erwartet: ${KEY_PATTERNS[k].source}`,
        });
      }
      config._switcher.keys[k] = v;
    }
  }

  const keys = config._switcher.keys;
  let routerNeedsRestart = false;

  if (provider === 'anthropic') {
    // OAuth via Claude Desktop, kein Key, kein Proxy
    delete config.env.ANTHROPIC_API_KEY;
    delete config.env.ANTHROPIC_BASE_URL;
    // Modell explizit setzen wenn vom UI gewählt — sonst nimmt Claude Code Plan-Default
    // (bei Pro: Sonnet als Default; bei Max kann Opus genutzt werden)
    if (model) {
      config.model = model;
    } else {
      delete config.model;
    }
  } else if (provider === 'google') {
    if (!keys.google) return res.status(400).json({ error: 'Google AI Studio API Key fehlt' });
    // Claude Code → Router → Google AI
    // settings.json.model MUSS ein gültiges Anthropic-Modell sein, sonst lehnt
    // Claude Code beim Start ab. Der Router routet alles via default-Route zum
    // tatsächlich gewählten Gemini-Modell (siehe writeRouterConfig).
    config.env.ANTHROPIC_API_KEY = 'sk-ccr-anything';
    config.env.ANTHROPIC_BASE_URL = HOST_ROUTER_URL;
    config.model = 'claude-sonnet-4-5-20250929';  // Anthropic-Alias für Validation
    config._switcher.activeRoute = { provider: 'google', model: model || 'gemini-2.5-pro' };
    routerNeedsRestart = true;
  } else if (provider === 'openrouter') {
    if (!keys.openrouter) return res.status(400).json({ error: 'OpenRouter API Key fehlt' });
    config.env.ANTHROPIC_API_KEY = 'sk-ccr-anything';
    config.env.ANTHROPIC_BASE_URL = HOST_ROUTER_URL;
    config.model = 'claude-sonnet-4-5-20250929';  // Anthropic-Alias für Validation
    config._switcher.activeRoute = { provider: 'openrouter', model: model || 'anthropic/claude-sonnet-4.5' };
    routerNeedsRestart = true;
  } else {
    return res.status(400).json({ error: `unknown provider: ${provider}` });
  }

  config._switcher.provider = provider;

  try {
    writeConfig(config);
    writeRouterConfig(config);
  } catch (e) {
    return res.status(500).json({ error: e.message });
  }

  let routerStatus = { ok: true, skipped: true };
  if (routerNeedsRestart) {
    routerStatus = await restartRouter();
  }

  // Wrapper-Restart-Marker schreiben → claude-auto sieht ihn und startet
  // claude mit --resume neu, sodass der neue Provider/Modell sofort greift
  // (sonst läuft das laufende claude mit der ALTEN settings.json-Config weiter).
  let wrapperNotified = false;
  try {
    const markerPath = path.join(path.dirname(CONFIG_PATH), '.switcher-restart');
    fs.writeFileSync(markerPath, JSON.stringify({
      at: Date.now(),
      reason: 'manual-switch',
      provider, model: config.model || null,
    }));
    wrapperNotified = true;
  } catch (e) {
    console.warn('Konnte Wrapper-Marker nicht schreiben:', e.message);
  }

  broadcast('switch', {
    provider,
    model: config.model || null,
    activeRoute: config._switcher?.activeRoute || null,
  });
  res.json({
    success: true,
    provider,
    model: config.model || null,
    router: routerStatus,
    wrapperNotified,
  });
});

// ─── Auto-Modus-Config ─────────────────────────────────────────────────────

app.get('/api/auto', (req, res) => {
  const config = readConfig();
  const s = config._switcher || {};
  res.json({
    mode: s.mode || 'manual',
    fallback_chain: s.fallback_chain || DEFAULT_CHAIN,
    chain_position: s.chain_position || 0,
    thresholds: s.thresholds || { warn_percent: 90 },
  });
});

app.post('/api/auto', (req, res) => {
  const { mode, fallback_chain, chain_position, thresholds } = req.body;
  const config = readConfig();
  if (!config._switcher) config._switcher = { keys: {} };

  if (mode) {
    if (!['manual', 'auto'].includes(mode)) {
      return res.status(400).json({ error: 'mode must be manual or auto' });
    }
    config._switcher.mode = mode;
  }
  if (fallback_chain && Array.isArray(fallback_chain)) {
    config._switcher.fallback_chain = fallback_chain;
    // Wenn Chain geändert, Position zurücksetzen
    config._switcher.chain_position = 0;
  }
  if (typeof chain_position === 'number') {
    config._switcher.chain_position = Math.max(0, chain_position);
  }
  if (thresholds) config._switcher.thresholds = thresholds;

  try {
    writeConfig(config);
    writeRouterConfig(config);
    broadcast('auto-config', {
      mode: config._switcher.mode,
      fallback_chain: config._switcher.fallback_chain,
      chain_position: config._switcher.chain_position,
    });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Manueller Chain-Reset (z.B. „neuer Tag, alle Provider wieder probieren")
app.post('/api/chain-reset', (req, res) => {
  const config = readConfig();
  if (!config._switcher) config._switcher = { keys: {} };
  config._switcher.chain_position = 0;
  try {
    writeConfig(config);
    broadcast('chain-reset', { chain_position: 0 });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Wrapper-Endpunkte ─────────────────────────────────────────────────────

// Wrapper meldet 90%-Warnung
app.post('/api/warn', (req, res) => {
  const { percent, project, source } = req.body;
  const config = readConfig();
  if (!config._switcher) config._switcher = { keys: {} };
  config._switcher.lastWarn = {
    percent: percent || null,
    project: project || null,
    source: source || null,
    at: Date.now(),
  };
  try { writeConfig(config); } catch {}
  broadcast('warn', config._switcher.lastWarn);
  res.json({ success: true });
});

// Wrapper meldet 100%-Quota-Fehler → rückt in der Failover-Chain vor
app.post('/api/quota-error', async (req, res) => {
  const { project, sessionId } = req.body;
  const config = readConfig();
  if (!config._switcher) config._switcher = { keys: {} };
  const s = config._switcher;

  broadcast('quota-error', { project, sessionId, mode: s.mode });

  if (s.mode !== 'auto') {
    return res.json({ action: 'notify', reason: 'auto-mode disabled' });
  }

  const chain = s.fallback_chain || DEFAULT_CHAIN;
  const currentProvider = deriveProvider(config);

  // Position berechnen: wenn aktuell auf primary (anthropic), starte bei 0.
  // Wenn aktuell auf chain[N] und der scheitert, gehe auf chain[N+1].
  let pos = s.chain_position || 0;
  if (currentProvider !== 'anthropic') {
    // Sichergehen, dass pos zur aktuellen Stufe passt; sonst nach vorne springen
    const matchIdx = chain.findIndex(
      (e) => e.provider === currentProvider && e.model === config.model
    );
    if (matchIdx >= 0) pos = matchIdx + 1;
  }

  // Suche nächste Stufe für die wir einen Key haben
  while (pos < chain.length) {
    const target = chain[pos];
    const keyName = target.provider === 'google' ? 'google'
                  : target.provider === 'openrouter' ? 'openrouter'
                  : null;
    if (keyName && s.keys && s.keys[keyName]) break;
    pos++;
  }

  if (pos >= chain.length) {
    s.chain_position = chain.length;
    s.lastFailoverAt = Date.now();
    try { writeConfig(config); } catch {}
    broadcast('chain-exhausted', { chain });
    return res.json({ action: 'exhausted', reason: 'alle Provider versagt oder kein Key' });
  }

  const target = chain[pos];

  // Switch auf target
  try {
    const newConfig = readConfig();
    if (!newConfig.env) newConfig.env = {};
    if (!newConfig._switcher) newConfig._switcher = { keys: {} };
    newConfig.env.ANTHROPIC_API_KEY = 'sk-ccr-anything';
    newConfig.env.ANTHROPIC_BASE_URL = HOST_ROUTER_URL;
    newConfig.model = target.model;
    newConfig._switcher.provider = target.provider;
    newConfig._switcher.chain_position = pos + 1; // nächstes Mal die übernächste
    newConfig._switcher.lastFailoverAt = Date.now();
    writeConfig(newConfig);
    writeRouterConfig(newConfig);
    await restartRouter();
    broadcast('auto-switched', { to: target, position: pos, total: chain.length });
    res.json({ action: 'switch', target, position: pos, total: chain.length });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Manueller Recheck-Trigger: zurück auf Primary (Anthropic)
app.post('/api/chain-promote', async (req, res) => {
  const config = readConfig();
  if (!config._switcher) config._switcher = { keys: {} };

  // Auf Anthropic zurück (OAuth-Pfad)
  if (config.env) {
    delete config.env.ANTHROPIC_API_KEY;
    delete config.env.ANTHROPIC_BASE_URL;
  }
  delete config.model;
  config._switcher.provider = 'anthropic';
  config._switcher.chain_position = 0;
  config._switcher.lastFailoverAt = null;

  try {
    writeConfig(config);
    writeRouterConfig(config);

    // Wrapper-Restart-Marker schreiben
    const markerPath = path.join(path.dirname(CONFIG_PATH), '.switcher-restart');
    fs.writeFileSync(markerPath, JSON.stringify({ at: Date.now(), reason: 'chain-promote' }));

    broadcast('chain-promoted', { to: 'anthropic' });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Recheck-Timer: alle 30 min auf Anthropic zurück testen ─────────────────
// Strategie: aggressiv probieren um Kosten zu minimieren. Wenn Anthropic noch
// voll → sofort wieder runter durch die Chain (~10 s Doppel-Restart). Wenn
// frei → kostenlos auf Pro/Max-Abo zurück.

const RECHECK_INTERVAL_MS = 30 * 60 * 1000; // alle 30 min Tick
const COOLDOWN_MS = 30 * 60 * 1000;          // Mindestabstand zum letzten Failover

async function autoPromoteIfDue() {
  try {
    const config = readConfig();
    const s = config._switcher;
    if (!s || s.mode !== 'auto') return;
    if (!s.chain_position || s.chain_position === 0) return; // schon auf Primary
    if (!s.lastFailoverAt) return;

    const age = Date.now() - s.lastFailoverAt;
    if (age < COOLDOWN_MS) return;

    // Cooldown abgelaufen → automatisch zurück. Wenn Anthropic noch voll ist,
    // läuft die Chain beim nächsten Request automatisch wieder durch.
    if (config.env) {
      delete config.env.ANTHROPIC_API_KEY;
      delete config.env.ANTHROPIC_BASE_URL;
    }
    delete config.model;
    s.provider = 'anthropic';
    s.chain_position = 0;
    s.lastFailoverAt = null;
    s.lastAutoPromoteAt = Date.now();

    writeConfig(config);
    writeRouterConfig(config);

    const markerPath = path.join(path.dirname(CONFIG_PATH), '.switcher-restart');
    fs.writeFileSync(markerPath, JSON.stringify({
      at: Date.now(),
      reason: 'auto-recheck',
      hoursSinceFailover: Math.round(age / 3600000 * 10) / 10,
    }));

    broadcast('auto-promoted', {
      reason: 'cooldown-elapsed',
      hoursSinceFailover: Math.round(age / 3600000 * 10) / 10,
    });
    console.log(`Auto-Promote: zurück zu Anthropic nach ${Math.round(age / 3600000 * 10) / 10} h`);
  } catch (e) {
    console.warn('auto-recheck error:', e.message);
  }
}

setInterval(autoPromoteIfDue, RECHECK_INTERVAL_MS);

// Manueller Trigger (z.B. „jetzt sofort prüfen, ungeachtet des Cooldowns")
app.post('/api/recheck-now', async (req, res) => {
  // Cooldown künstlich überschreiben
  const config = readConfig();
  if (config._switcher) config._switcher.lastFailoverAt = Date.now() - COOLDOWN_MS - 1000;
  try { writeConfig(config); } catch {}
  await autoPromoteIfDue();
  res.json({ success: true });
});

// ─── Restart-Signal: schreibt Marker damit Wrapper Claude killt + --resume ──
// Wrapper pollt ~/.claude/.switcher-restart alle 5s. Provider/Modell werden
// NICHT geändert — nur Claude wird neu gestartet damit settings.json greift.

app.post('/api/restart', (req, res) => {
  try {
    const markerPath = path.join(path.dirname(CONFIG_PATH), '.switcher-restart');
    fs.writeFileSync(markerPath, JSON.stringify({
      at: Date.now(),
      reason: 'manual-ui-restart',
    }));
    // Legacy-Signal bleibt für Backwards-Compat (alte Wrapper-Versionen)
    const legacyPath = path.join(path.dirname(CONFIG_PATH), '.restart-signal');
    fs.writeFileSync(legacyPath, Date.now().toString());
    broadcast('restart-requested', { source: 'ui' });
    res.json({ success: true, marker: markerPath });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Boot ──────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`Claude Switcher v2 → http://localhost:${PORT}`);
  console.log(`Settings:      ${CONFIG_PATH}`);
  console.log(`Router-Config: ${ROUTER_CONFIG_PATH}`);
  console.log(`Router:        ${ROUTER_CONTAINER} (${ROUTER_BASE_URL})`);

  // Beim Start: Router-Config schreiben falls noch keine vorhanden, damit Router aufstarten kann.
  try {
    if (!fs.existsSync(ROUTER_CONFIG_PATH)) {
      writeRouterConfig(readConfig());
      console.log('Initial router-config geschrieben.');
    }
  } catch (e) {
    console.warn('Konnte initiale router-config nicht schreiben:', e.message);
  }
});
