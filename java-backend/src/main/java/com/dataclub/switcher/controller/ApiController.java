package com.dataclub.switcher.controller;

import com.dataclub.switcher.model.AiModelConfig;
import com.dataclub.switcher.service.ConfigService;
import com.dataclub.switcher.service.LlmCascadeClient;
import com.dataclub.switcher.service.RouterService;
import com.dataclub.switcher.service.SseService;
import com.dataclub.switcher.service.SwitcherModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.regex.Pattern;

/**
 * HTTP-API des Switchers — vollstaendiger Port der Node-server.js Endpoints.
 *
 * Endpoints:
 *   GET  /api/status            — voller State (provider, keys masked, chain, etc.)
 *   GET  /api/whoami            — plain-text Modell-Identitaet
 *   GET  /api/banner            — quota-warn-snippet fuer Wrapper (max 5min frisch)
 *   GET  /api/key/{provider}    — voller Key (nur localhost!)
 *   POST /api/switch            — Provider/Modell wechseln + ccr-Restart + Marker
 *   GET  /api/auto              — auto-mode + chain + position + thresholds
 *   POST /api/auto              — mode/chain/position/thresholds setzen
 *   POST /api/warn              — Wrapper meldet 90%-Warnung
 *   POST /api/quota-error       — Wrapper meldet 100%; auto→failover, manual→banner
 *   POST /api/chain-reset       — chain_position = 0
 *   POST /api/chain-promote     — zurueck auf Anthropic primary
 *   POST /api/recheck-now       — manueller auto-promote-Trigger
 *   POST /api/restart           — Wrapper-Restart-Marker schreiben
 *   GET  /api/events            — SSE-Stream fuer UI-Updates
 *   GET  /api/cascade-health    — llm-cascade-Reachability
 *   GET  /api/cascade-models    — Modell-Liste aus llm-cascade
 *   GET  /api/cascades          — Cascade-Bereiche (Proxy zu llm-cascade, fuer ki-cascades-view)
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired private ConfigService configs;
    @Autowired private RouterService router;
    @Autowired private SseService sse;
    @Autowired private LlmCascadeClient cascade;
    /** Lokaler Modell-Context (Phase K, 2026-05-14) — ersetzt HTTP-Proxy zur Cascade fuer CRUD. */
    @Autowired private SwitcherModelService modelSvc;

    private static final String HOST_ROUTER_URL = "http://localhost:3456";
    private static final long FRESH_BANNER_MS = 5L * 60_000L;
    public  static final long COOLDOWN_MS     = 30L * 60_000L;

    private static final Map<String, String> CASCADE_TO_SWITCHER = Map.of(
        "gemini", "google", "anthropic", "anthropic", "openrouter", "openrouter"
    );

    private static final Map<String, Pattern> KEY_PATTERNS = Map.of(
        "anthropic",  Pattern.compile("^sk-ant-(api03|oat01)-"),
        "google",     Pattern.compile("^AIza[A-Za-z0-9_-]{30,}$"),
        "openrouter", Pattern.compile("^sk-or-[A-Za-z0-9_-]{15,}$")
    );

    private static final Map<String, String> PRETTY_NAMES = new HashMap<>() {{
        put("claude-opus-4-7", "Claude Opus 4.7");
        put("claude-sonnet-4-6", "Claude Sonnet 4.6");
        put("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5");
        put("claude-haiku-4-5-20251001", "Claude Haiku 4.5");
        put("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet");
        put("gemini-2.5-pro", "Gemini 2.5 Pro");
        put("gemini-2.5-flash", "Gemini 2.5 Flash");
        put("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite");
        put("gemini-3-pro-preview", "Gemini 3 Pro (Preview)");
        put("gemini-3-flash-preview", "Gemini 3 Flash (Preview)");
    }};
    private static String pretty(String id) { return id == null ? "?" : PRETTY_NAMES.getOrDefault(id, id); }

    // ─── Status ──────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> status() {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw  = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        ObjectNode keys = sw.has("keys") && sw.get("keys").isObject()
            ? (ObjectNode) sw.get("keys") : configs.mapper().createObjectNode();
        ArrayNode chain = sw.has("fallback_chain") && sw.get("fallback_chain").isArray()
            ? (ArrayNode) sw.get("fallback_chain") : configs.defaultChain();
        int position = sw.path("chain_position").asInt(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", configs.deriveProvider(cfg));
        out.put("model", cfg.path("model").isTextual() ? cfg.get("model").asText() : null);
        out.put("activeRoute", sw.has("activeRoute") ? sw.get("activeRoute") : null);
        out.put("mode", sw.path("mode").asText("manual"));
        // 2-Achsen-Supermodell: Pool (cloud|free|local) + Supermodell-Schalter.
        out.put("pool", sw.path("pool").asText("cloud"));
        out.put("supermodel", sw.path("supermodel").asBoolean(false));
        out.put("localOrchestratorPending", sw.path("localOrchestratorPending").asBoolean(false));
        out.put("fallback_chain", chain);
        out.put("chain_position", position);
        out.put("chain_exhausted", position >= chain.size());
        out.put("fallback", chain.size() > 0 ? chain.get(0) : null);
        out.put("anthropicKeyMasked", mask(keys.path("anthropic").asText("")));
        out.put("googleKeyMasked", mask(keys.path("google").asText("")));
        out.put("openrouterKeyMasked", mask(keys.path("openrouter").asText("")));
        out.put("hasAnthropicKey", !keys.path("anthropic").asText("").isBlank());
        out.put("hasGoogleKey", !keys.path("google").asText("").isBlank());
        out.put("hasOpenRouterKey", !keys.path("openrouter").asText("").isBlank());
        out.put("lastWarn", sw.has("lastWarn") && !sw.get("lastWarn").isNull() ? sw.get("lastWarn") : null);
        return out;
    }

    @GetMapping(value = "/whoami", produces = MediaType.TEXT_PLAIN_VALUE)
    public String whoami() {
        ObjectNode cfg = configs.readConfig();
        String provider = configs.deriveProvider(cfg);
        JsonNode ar = cfg.path("_switcher").path("activeRoute");
        if (ar.isObject() && ("google".equals(provider) || "openrouter".equals(provider))) {
            String model = ar.path("model").asText("?");
            if ("google".equals(provider)) {
                return pretty(model) + " via Google AI Studio (Router) — entwickelt von Google";
            }
            String[] parts = model.split("/");
            String vendor = parts.length > 0 ? parts[0] : "unknown";
            String builder = switch (vendor) {
                case "anthropic"  -> "Anthropic";
                case "google"     -> "Google";
                case "meta-llama" -> "Meta";
                case "openai"     -> "OpenAI";
                case "deepseek"   -> "DeepSeek";
                default -> vendor;
            };
            return model + " via OpenRouter — entwickelt von " + builder;
        }
        String m = cfg.path("model").asText("claude-sonnet-4-5-20250929");
        return pretty(m) + " (Anthropic direkt) — entwickelt von Anthropic";
    }

    @GetMapping(value = "/banner", produces = MediaType.TEXT_PLAIN_VALUE)
    public synchronized String banner() {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : null;
        if (sw == null) return "";
        JsonNode lastWarn = sw.path("lastWarn");
        if (!lastWarn.isObject()) return "";
        long at = lastWarn.path("at").asLong(0);
        if (at == 0 || System.currentTimeMillis() - at > FRESH_BANNER_MS) return "";

        int pos = sw.path("chain_position").asInt(0);
        ArrayNode chain = sw.has("fallback_chain") && sw.get("fallback_chain").isArray()
            ? (ArrayNode) sw.get("fallback_chain") : configs.defaultChain();
        String nextName = pos < chain.size() ? pretty(chain.get(pos).path("model").asText()) : "Gemini Pro";
        int pct = lastWarn.path("percent").asInt(0);

        String line;
        if (pct >= 100) {
            line = "[SWITCHER-EVENT] Anthropic-Quota voll (100%). Im UI auf " + nextName
                 + " wechseln oder im Chat sagen \"wechsel auf gemini pro\". "
                 + "Sage dem User: \"⚠ Anthropic-Quota erreicht — switch auf " + nextName
                 + " empfohlen. Sag 'wechsel auf gemini pro' damit ich umstelle.\"";
        } else {
            line = "[SWITCHER-EVENT] Anthropic-Quota bei " + pct + "%. Manueller Modus: User entscheidet. "
                 + "Sage dem User: \"⚠ Anthropic-Quota bei " + pct + "% — bei 100% empfehle ich Wechsel auf "
                 + nextName + ". Sag dann 'wechsel auf gemini pro'.\"";
        }

        // Einmal-Snippet: nach dem Lesen wegwerfen
        sw.putNull("lastWarn");
        configs.writeConfig(cfg);
        return line;
    }

    @GetMapping("/key/{provider}")
    public ResponseEntity<?> getKey(@PathVariable String provider) {
        if (!Set.of("anthropic", "google", "openrouter").contains(provider)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown provider"));
        }
        // resolveKey: google/openrouter aus der DB (app_settings), anthropic aus
        // settings.json — derselbe Key, den auch der Router nutzt (konsistente Anzeige).
        String k = router.resolveKey(provider);
        return ResponseEntity.ok(Map.of("provider", provider, "key", k));
    }

    // ─── Switch ──────────────────────────────────────────────────────────────

    public static class SwitchRequest {
        public String provider;
        public String model;
        // Nur anthropic (OAuth/Long-Token → settings.json/Wrapper). google/openrouter
        // pflegt ki-models-ui in der DB (app_settings) — nicht mehr über den Switch.
        public String anthropicKey;
    }

    @PostMapping("/switch")
    public synchronized ResponseEntity<?> doSwitch(@RequestBody SwitchRequest req) {
        if (req == null || req.provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider required"));
        }
        ObjectNode cfg = configs.readConfig();
        ObjectNode env = cfg.has("env") && cfg.get("env").isObject() ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject() ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        ObjectNode keys = sw.has("keys") && sw.get("keys").isObject() ? (ObjectNode) sw.get("keys") : configs.mapper().createObjectNode();

        // Nur anthropic (OAuth/Long-Token) wird hier noch in settings.json gepflegt.
        // google/openrouter leben in der DB (app_settings) — ki-models-ui pflegt sie,
        // der Router liest sie via resolveKey(). Kein settings.json-Schreiben mehr
        // für sie = keine Divergenz (war die Ursache des „API key not valid"-Bugs).
        String aKey = req.anthropicKey;
        if (aKey != null && !aKey.isEmpty() && !"__UNCHANGED__".equals(aKey)) {
            if (!KEY_PATTERNS.get("anthropic").matcher(aKey).find()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "anthropic-Key hat falsches Format. Erwartet: " + KEY_PATTERNS.get("anthropic").pattern()));
            }
            keys.put("anthropic", aKey);
        }

        boolean routerNeedsRestart = false;
        if ("anthropic".equals(req.provider)) {
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            if (req.model != null) cfg.put("model", req.model); else cfg.remove("model");
            sw.remove("activeRoute");
        } else if ("google".equals(req.provider)) {
            if (router.resolveKey("google").isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "Google AI Studio API Key fehlt"));
            Set<String> valid = Set.of("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
                                        "gemini-3-pro-preview", "gemini-3-flash-preview");
            String safe = valid.contains(req.model) ? req.model : "gemini-2.5-pro";
            env.put("ANTHROPIC_API_KEY", "sk-ccr-anything");
            env.put("ANTHROPIC_BASE_URL", HOST_ROUTER_URL);
            cfg.put("model", "claude-sonnet-4-5-20250929");
            ObjectNode ar = configs.mapper().createObjectNode();
            ar.put("provider", "google"); ar.put("model", safe);
            sw.set("activeRoute", ar);
            routerNeedsRestart = true;
        } else if ("openrouter".equals(req.provider)) {
            if (router.resolveKey("openrouter").isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "OpenRouter API Key fehlt"));
            String safe = (req.model != null && req.model.contains("/")) ? req.model : "anthropic/claude-sonnet-4.5";
            env.put("ANTHROPIC_API_KEY", "sk-ccr-anything");
            env.put("ANTHROPIC_BASE_URL", HOST_ROUTER_URL);
            cfg.put("model", "claude-sonnet-4-5-20250929");
            ObjectNode ar = configs.mapper().createObjectNode();
            ar.put("provider", "openrouter"); ar.put("model", safe);
            sw.set("activeRoute", ar);
            routerNeedsRestart = true;
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown provider: " + req.provider));
        }

        sw.put("provider", req.provider);
        sw.set("keys", keys);
        cfg.set("env", env);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();

        boolean routerOk = !routerNeedsRestart || router.restartRouter();

        ObjectNode marker = configs.mapper().createObjectNode();
        marker.put("provider", req.provider);
        if (cfg.has("model")) marker.set("model", cfg.get("model"));
        configs.writeRestartMarker("manual-switch", marker);

        // Map.of() lehnt null-Werte ab → bei Switch zu Anthropic ist activeRoute
        // bewusst null (siehe `sw.remove("activeRoute")` oben). HashMap erlaubt
        // nulls und löst das Problem.
        Map<String, Object> switchEvent = new HashMap<>();
        switchEvent.put("provider", req.provider);
        switchEvent.put("model", cfg.has("model") ? cfg.get("model").asText() : null);
        switchEvent.put("activeRoute", sw.has("activeRoute") ? sw.get("activeRoute") : null);
        sse.broadcast("switch", switchEvent);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", routerOk);
        out.put("provider", req.provider);
        out.put("model", cfg.has("model") ? cfg.get("model").asText() : null);
        out.put("router", Map.of("ok", routerOk, "restarted", routerNeedsRestart));
        out.put("wrapperNotified", true);
        return ResponseEntity.ok(out);
    }

    // ─── Auto-Mode-Config ────────────────────────────────────────────────────

    @GetMapping("/auto")
    public Map<String, Object> getAuto() {
        ObjectNode sw = configs.getSwitcher();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", sw.path("mode").asText("manual"));
        out.put("fallback_chain", sw.has("fallback_chain") ? sw.get("fallback_chain") : configs.defaultChain());
        out.put("chain_position", sw.path("chain_position").asInt(0));
        out.put("thresholds", sw.has("thresholds") ? sw.get("thresholds")
            : configs.mapper().createObjectNode().put("warn_percent", 90));
        return out;
    }

    public static class AutoRequest {
        public String mode;
        public List<Map<String, String>> fallback_chain;
        public Integer chain_position;
        public Map<String, Object> thresholds;
    }

    @PostMapping("/auto")
    public synchronized ResponseEntity<?> setAuto(@RequestBody AutoRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();

        if (req.mode != null) {
            if (!Set.of("manual", "auto").contains(req.mode))
                return ResponseEntity.badRequest().body(Map.of("error", "mode must be manual or auto"));
            sw.put("mode", req.mode);
        }
        if (req.fallback_chain != null) {
            ArrayNode arr = configs.mapper().createArrayNode();
            for (Map<String, String> e : req.fallback_chain) {
                ObjectNode n = configs.mapper().createObjectNode();
                n.put("provider", e.get("provider"));
                n.put("model", e.get("model"));
                arr.add(n);
            }
            sw.set("fallback_chain", arr);
            sw.put("chain_position", 0);
        }
        if (req.chain_position != null) sw.put("chain_position", Math.max(0, req.chain_position));
        if (req.thresholds != null) sw.set("thresholds", configs.mapper().valueToTree(req.thresholds));

        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        sse.broadcast("auto-config", Map.of(
            "mode", sw.path("mode").asText("manual"),
            "fallback_chain", sw.has("fallback_chain") ? sw.get("fallback_chain") : configs.defaultChain()));
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ─── Chain ───────────────────────────────────────────────────────────────

    @PostMapping("/chain-reset")
    public synchronized Map<String, Object> chainReset() {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        sw.put("chain_position", 0);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        sse.broadcast("chain-reset", Map.of("chain_position", 0));
        return Map.of("success", true);
    }

    @PostMapping("/chain-promote")
    public synchronized Map<String, Object> chainPromote() {
        ObjectNode cfg = configs.readConfig();
        ObjectNode env = cfg.has("env") && cfg.get("env").isObject() ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject() ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        env.remove("ANTHROPIC_API_KEY");
        env.remove("ANTHROPIC_BASE_URL");
        cfg.remove("model");
        sw.put("provider", "anthropic");
        sw.put("chain_position", 0);
        sw.putNull("lastFailoverAt");
        sw.remove("activeRoute");
        cfg.set("env", env);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        configs.writeRestartMarker("chain-promote", null);
        sse.broadcast("chain-promoted", Map.of("to", "anthropic"));
        return Map.of("success", true);
    }

    // ─── Supermodell-Modus — 2 Achsen: Pool (cloud|free|local) × Supermodell ──
    // Pool wählt das Modellset + Privacy-Lane; Supermodell schaltet die
    // Opus-Orchestrierung (@supermodel-Delegation, siehe ~/.claude/CLAUDE.md)
    // darüber an/aus. KRITISCH: Pool=local ist FAIL-CLOSED — der Orchestrator
    // bleibt lokal, NIE wird automatisch auf Opus/Anthropic/Cloud gepinnt.

    private static final Set<String> POOLS = Set.of("cloud", "free", "local");

    @GetMapping("/supermodel")
    public Map<String, Object> getSupermodel() {
        ObjectNode sw = configs.getSwitcher();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", sw.path("supermodel").asBoolean(false));
        out.put("pool", sw.path("pool").asText("cloud"));
        out.put("localOrchestratorPending", sw.path("localOrchestratorPending").asBoolean(false));
        return out;
    }

    public static class SupermodelRequest { public Boolean enabled; }

    /**
     * Back-compat-Endpoint: nur die Supermodell-Achse umschalten, Pool unverändert.
     * Delegiert an {@link #setMode}. Antwortform der alten API bleibt
     * {@code {success, enabled, restart}}.
     */
    @PostMapping("/supermodel")
    public synchronized ResponseEntity<?> setSupermodel(@RequestBody SupermodelRequest req) {
        ModeRequest m = new ModeRequest();
        m.supermodel = req != null && Boolean.TRUE.equals(req.enabled);
        ResponseEntity<?> resp = setMode(m);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() instanceof Map<?, ?> body) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", body.get("success"));
            out.put("enabled", body.get("supermodel"));
            out.put("restart", body.get("restart"));
            return ResponseEntity.ok(out);
        }
        return resp;
    }

    public static class ModeRequest {
        public String pool;        // cloud | free | local
        public Boolean supermodel; // null = unverändert lassen
    }

    /**
     * Pool-bewusster 2-Achsen-Modus. Body {@code {pool?, supermodel?}} (beide
     * optional → partielle Updates). Persistiert {@code _switcher.pool} +
     * {@code _switcher.supermodel}.
     *
     * Orchestrator-Pinning (nur wenn supermodel=true):
     * <ul>
     *   <li><b>cloud/free</b> → Opus (Anthropic direkt) pinnen, Opus→Sonnet-Failover
     *       bleibt erhalten. Idempotent (kein Restart wenn schon Anthropic).</li>
     *   <li><b>local</b> → FAIL-CLOSED: NIE Opus/Anthropic/Cloud pinnen. Der
     *       Orchestrator muss lokal sein; solange kein lokales Modell aktiv ist,
     *       wird {@code localOrchestratorPending=true} gesetzt — aber niemals als
     *       „keep-alive" auf die Cloud ausgewichen. Lieber STOPP als Leak.</li>
     * </ul>
     */
    @PostMapping("/mode")
    public synchronized ResponseEntity<?> setMode(@RequestBody ModeRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();

        // 1) Pool (validiert + persistiert; Default cloud)
        String pool = sw.path("pool").asText("cloud");
        if (req != null && req.pool != null && !req.pool.isBlank()) {
            if (!POOLS.contains(req.pool))
                return ResponseEntity.badRequest().body(Map.of("error", "pool must be cloud|free|local"));
            pool = req.pool;
        }
        sw.put("pool", pool);

        // 2) Supermodell (null = unverändert)
        boolean superOn = req != null && req.supermodel != null
            ? req.supermodel : sw.path("supermodel").asBoolean(false);
        sw.put("supermodel", superOn);

        // 3) Orchestrator pool-bewusst pinnen
        boolean needRestart = false;
        if (superOn) {
            if ("local".equals(pool)) {
                // Local = fail-closed: KEIN Auto-Failover (sonst Cloud-Ausweich).
                sw.put("mode", "manual");
            } else {
                // cloud/free: Orchestrator-Failover scharf stellen → Opus am Limit
                // schaltet automatisch durch die orchestrator-{pool}-Zelle — DATENGETRIEBEN
                // (editierbar wie die anderen Rollen, mit Reihenfolge); Cooldown-AutoPromote
                // (AutoPromoteService, 30 min) holt Opus zurück.
                sw.put("mode", "auto");
                sw.set("fallback_chain", orchestratorFailoverChain(pool));
                sw.put("chain_position", 0);
            }
            needRestart = pinOrchestratorForPool(cfg, sw, pool);
        } else {
            // Supermodell aus: Mode unberührt lassen (User-Einstellung), nur Pending räumen.
            sw.remove("localOrchestratorPending");
        }

        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        if (needRestart) {
            configs.writeRestartMarker("local".equals(pool) ? "supermodel-local" : "supermodel-on", null);
        }

        boolean pending = sw.path("localOrchestratorPending").asBoolean(false);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("pool", pool);
        ev.put("supermodel", superOn);
        ev.put("localOrchestratorPending", pending);
        sse.broadcast("mode", ev);
        // Back-compat: das Frontend hört noch auf 'supermodel'.
        sse.broadcast("supermodel", Map.of("enabled", superOn, "pool", pool));

        Map<String, Object> out = new LinkedHashMap<>(ev);
        out.put("success", true);
        out.put("restart", needRestart);
        if (superOn && "local".equals(pool) && pending) {
            out.put("note", "Lokaler Orchestrator gewählt, aber kein lokales Modell aktiv. "
                + "Fail-closed: kein Cloud-Ausweich. Ollama-Modell ziehen + aktivieren.");
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Failover-Kette für den Supermodell-Orchestrator (cloud/free): zuerst Sonnet
     * (Anthropic-direkt → nativ, Subagents bleiben, Supermodell intakt), dann Cloud
     * via ccr (degradiert-aber-läuft). AutoPromoteService holt Opus nach Cooldown zurück.
     */
    ArrayNode supermodelFailoverChain() {
        ArrayNode chain = configs.mapper().createArrayNode();
        ObjectNode s = configs.mapper().createObjectNode(); s.put("provider", "anthropic"); s.put("model", "claude-sonnet-4-6"); chain.add(s);
        ObjectNode g = configs.mapper().createObjectNode(); g.put("provider", "google");    g.put("model", "gemini-2.5-pro");   chain.add(g);
        ObjectNode f = configs.mapper().createObjectNode(); f.put("provider", "google");    f.put("model", "gemini-2.5-flash"); chain.add(f);
        return chain;
    }

    /**
     * DATENGETRIEBENE Orchestrator-Failover-Kette aus der {@code orchestrator-{pool}}-Zelle
     * — editierbar wie jede andere Rolle (Modelle hinzufügen/entfernen/umsortieren = orderIdx).
     * Opus am Limit schaltet der Reihe nach durch genau diese Modelle (Cooldown-Failover),
     * {@code AutoPromoteService} holt Opus nach 30-min-Cooldown zurück. Nur Cloud-fähige
     * Provider (anthropic/google/openrouter); Ollama/lokal wird übersprungen (lokaler
     * Hauptloop = Phase E, kein Cloud-Failover-Ziel — fail-closed bleibt). Leere/keine Zelle
     * → {@link #supermodelFailoverChain()} als Sicherheitsnetz (Opus nie ganz ohne Fallback).
     */
    ArrayNode orchestratorFailoverChain(String pool) {
        String cat = "orchestrator-" + pool;
        java.util.List<AiModelConfig> ms = new java.util.ArrayList<>();
        for (AiModelConfig m : modelSvc.listModels()) {
            if (cat.equals(m.getCategory()) && Boolean.TRUE.equals(m.getEnabled())) ms.add(m);
        }
        ms.sort(java.util.Comparator.comparingInt(m -> m.getOrderIdx() == null ? Integer.MAX_VALUE : m.getOrderIdx()));
        ArrayNode chain = configs.mapper().createArrayNode();
        for (AiModelConfig m : ms) {
            String swProv = CASCADE_TO_SWITCHER.getOrDefault(m.getProvider(), m.getProvider());
            if (!CASCADE_TO_SWITCHER.containsValue(swProv)) continue; // ollama/openai_compat = lokal → kein Cloud-Failover
            ObjectNode e = configs.mapper().createObjectNode();
            e.put("provider", swProv);
            e.put("model", m.getModelId());
            chain.add(e);
        }
        return chain.isEmpty() ? supermodelFailoverChain() : chain;
    }

    /**
     * Pool-bewusstes Orchestrator-Pinning. cloud/free → Opus; local → fail-closed
     * (nie Cloud). Gibt zurück, ob ein Wrapper-Restart nötig ist.
     */
    private boolean pinOrchestratorForPool(ObjectNode cfg, ObjectNode sw, String pool) {
        if ("local".equals(pool)) {
            // FAIL-CLOSED: in Local NIE auf Anthropic/Cloud pinnen. Wir lösen
            // keinen Cloud-Pin aus; ob ein lokales Orchestrator-Modell verfügbar
            // ist, signalisiert localOrchestratorPending (echtes ccr-Routing aufs
            // lokale Main-Loop-Modell ist Phase E, sobald Ollama-Modelle da sind).
            sw.put("localOrchestratorPending", !hasEnabledLocalOrchestrator());
            return false;
        }
        // cloud/free → Opus pinnen (idempotent, analog chain-promote)
        sw.remove("localOrchestratorPending");
        if (!"anthropic".equals(sw.path("provider").asText(""))) {
            ObjectNode env = cfg.has("env") && cfg.get("env").isObject()
                ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            cfg.set("env", env);
            sw.put("provider", "anthropic");
            sw.put("chain_position", 0);
            sw.remove("activeRoute");
            return true;
        }
        return false;
    }

    /** Existiert ein aktiviertes lokales (Ollama/openai_compat) Modell als Orchestrator? */
    private boolean hasEnabledLocalOrchestrator() {
        for (AiModelConfig m : modelSvc.listModels()) {
            if (!Boolean.TRUE.equals(m.getEnabled())) continue;
            String p = m.getProvider() == null ? "" : m.getProvider().toLowerCase();
            if (p.equals("ollama") || p.equals("openai_compat")) return true;
        }
        return false;
    }

    // ─── Wrapper-Endpoints (warn / quota-error / recheck-now / restart) ──────

    public static class WarnRequest {
        public Integer percent;
        public String project;
        public String source;
    }

    @PostMapping("/warn")
    public synchronized Map<String, Object> warn(@RequestBody WarnRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject() ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        ObjectNode lw = configs.mapper().createObjectNode();
        lw.put("percent", req.percent);
        lw.put("project", req.project);
        lw.put("source", req.source);
        lw.put("at", System.currentTimeMillis());
        sw.set("lastWarn", lw);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        sse.broadcast("warn", lw);
        return Map.of("success", true);
    }

    public static class QuotaErrorRequest {
        public String project;
        public String sessionId;
    }

    @PostMapping("/quota-error")
    public synchronized Map<String, Object> quotaError(@RequestBody QuotaErrorRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject() ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();

        // Map.of erlaubt keine null-Werte → HashMap + putIfNotNull
        Map<String, Object> ev = new HashMap<>();
        if (req != null && req.project   != null) ev.put("project",   req.project);
        if (req != null && req.sessionId != null) ev.put("sessionId", req.sessionId);
        ev.put("mode", sw.path("mode").asText("manual"));
        sse.broadcast("quota-error", ev);

        // Pool-Guard (fail-closed): in Local NIE automatisch wechseln — auch falls
        // mode irgendwie auf auto steht (defense-in-depth, kein Cloud-Leak).
        String pool = sw.path("pool").asText("cloud");
        if (!"auto".equals(sw.path("mode").asText("manual")) || "local".equals(pool)) {
            ObjectNode lw = configs.mapper().createObjectNode();
            lw.put("percent", 100);
            lw.put("project", req == null ? null : req.project);
            lw.put("source", "wrapper-quota-error");
            lw.put("at", System.currentTimeMillis());
            sw.set("lastWarn", lw);
            cfg.set("_switcher", sw);
            configs.writeConfig(cfg);
            return Map.of("action", "notify", "reason",
                "local".equals(pool) ? "local fail-closed (kein Cloud-Ausweich)" : "auto-mode disabled");
        }

        ArrayNode chain = sw.has("fallback_chain") && sw.get("fallback_chain").isArray()
            ? (ArrayNode) sw.get("fallback_chain") : configs.defaultChain();
        String currentProvider = configs.deriveProvider(cfg);
        int pos = sw.path("chain_position").asInt(0);

        if (!"anthropic".equals(currentProvider)) {
            String currentModel = cfg.path("model").asText("");
            for (int i = 0; i < chain.size(); i++) {
                if (currentProvider.equals(chain.get(i).path("provider").asText())
                        && currentModel.equals(chain.get(i).path("model").asText())) {
                    pos = i + 1; break;
                }
            }
        }

        ObjectNode keys = sw.has("keys") && sw.get("keys").isObject() ? (ObjectNode) sw.get("keys") : configs.mapper().createObjectNode();
        while (pos < chain.size()) {
            String tProv = chain.get(pos).path("provider").asText();
            // Anthropic ist immer reachable — Claude Code authentifiziert sich
            // beim Anthropic-direkt-Routing über sein eigenes OAuth/Login
            // (Max/Pro-Abo), KEIN Switcher-Key nötig.
            if ("anthropic".equals(tProv)) break;
            String keyName = "google".equals(tProv) ? "google" : "openrouter".equals(tProv) ? "openrouter" : null;
            if (keyName != null && !router.resolveKey(keyName).isBlank()) break;
            pos++;
        }

        if (pos >= chain.size()) {
            sw.put("chain_position", chain.size());
            sw.put("lastFailoverAt", System.currentTimeMillis());
            cfg.set("_switcher", sw);
            configs.writeConfig(cfg);
            sse.broadcast("chain-exhausted", Map.of("chain", chain));
            return Map.of("action", "exhausted", "reason", "alle Provider versagt oder kein Key");
        }

        ObjectNode target = (ObjectNode) chain.get(pos);
        String fromModel = cfg.path("model").asText(null);
        ObjectNode env = cfg.has("env") && cfg.get("env").isObject() ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
        boolean targetIsAnthropic = "anthropic".equals(target.path("provider").asText());
        if (targetIsAnthropic) {
            // Anthropic-nativ (z.B. Opus→Sonnet): KEIN ccr → native Claude-Code-Subagents/
            // MCP bleiben, Supermodell läuft weiter (nur schwächerer Orchestrator).
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            cfg.put("model", target.path("model").asText("claude-sonnet-4-6"));
            sw.remove("activeRoute");
        } else {
            // Cloud (Gemini/OpenRouter) via ccr — degradiert (keine Subagents), aber läuft.
            env.put("ANTHROPIC_API_KEY", "sk-ccr-anything");
            env.put("ANTHROPIC_BASE_URL", HOST_ROUTER_URL);
            cfg.put("model", "claude-sonnet-4-5-20250929");
            sw.set("activeRoute", target.deepCopy());
        }
        sw.put("provider", target.path("provider").asText());
        sw.put("chain_position", pos + 1);
        sw.put("lastFailoverAt", System.currentTimeMillis());
        ObjectNode lastSwitch = configs.mapper().createObjectNode();
        lastSwitch.put("at", System.currentTimeMillis());
        ObjectNode from = configs.mapper().createObjectNode();
        from.put("provider", currentProvider);
        from.put("model", fromModel);
        lastSwitch.set("from", from);
        lastSwitch.set("to", target.deepCopy());
        lastSwitch.put("reason", "quota");
        sw.set("lastAutoSwitch", lastSwitch);
        cfg.set("env", env);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        if (!targetIsAnthropic) router.restartRouter(); // ccr-Restart nur für Cloud-Targets
        sse.broadcast("auto-switched", Map.of("to", target, "position", pos, "total", chain.size()));
        return Map.of("action", "switch", "target", target, "position", pos, "total", chain.size());
    }

    @PostMapping("/recheck-now")
    public Map<String, Object> recheckNow() {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject() ? (ObjectNode) cfg.get("_switcher") : null;
        if (sw != null) {
            sw.put("lastFailoverAt", System.currentTimeMillis() - COOLDOWN_MS - 1000);
            cfg.set("_switcher", sw);
            configs.writeConfig(cfg);
        }
        // AutoPromoteService faengt das beim naechsten Tick — wir koennten hier auch direkt rufen.
        return Map.of("success", true);
    }

    @PostMapping("/restart")
    public Map<String, Object> restart() {
        configs.writeRestartMarker("manual-ui-restart", null);
        sse.broadcast("restart-requested", Map.of("source", "ui"));
        return Map.of("success", true);
    }

    // ─── SSE ─────────────────────────────────────────────────────────────────

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() { return sse.register(); }

    // ─── Cascade-Proxy ───────────────────────────────────────────────────────

    @GetMapping("/cascade-health")
    public Map<String, Object> cascadeHealth() {
        return Map.of("ok", cascade.isHealthy(), "url", cascade.url());
    }

    /**
     * Proxy zu llm-cascade GET /api/cascades.
     *
     * Liefert alle Cascade-Bereiche dynamisch (distinct category aus der DB).
     * Wird vom Frontend-Library-Component {@code <ki-cascades-view>} genutzt,
     * der per {@code KI_MODELS_API_BASE='/api'} auf diesen Endpoint zugreift.
     *
     * Fallback: liefert leeres Array wenn llm-cascade nicht erreichbar.
     */
    @GetMapping("/cascades")
    public com.fasterxml.jackson.databind.JsonNode cascades() {
        JsonNode all = cascade.getCascades();
        if (all == null || !all.isArray()) return all;
        // Nur die Cascaden des aktiven Pools zeigen (Übersichtlichkeit) — der
        // Bereich-Toggle wählt cloud|free|local, die Cascade-Bereiche-View
        // spiegelt nur diesen Pool. Compound-Kategorien {rolle}-{pool} + die
        // Pool-Kategorie selbst zählen dazu.
        String pool = configs.getSwitcher().path("pool").asText("cloud");
        ArrayNode out = configs.mapper().createArrayNode();
        for (JsonNode c : all) {
            if (matchesPool(c.path("name").asText(""), pool)) out.add(c);
        }
        return out;
    }

    /** Backend-Spiegel der Frontend-matchesPool: Kategorie == Pool ODER endet auf
     *  -pool; free matcht zusätzlich die Legacy-Kategorie free-only. */
    static boolean matchesPool(String cat, String pool) {
        if (cat == null || cat.isBlank()) return false;
        return switch (pool) {
            case "free"  -> cat.equals("free") || cat.equals("free-only") || cat.endsWith("-free");
            case "local" -> cat.equals("local") || cat.endsWith("-local");
            default      -> cat.equals("cloud") || cat.endsWith("-cloud");
        };
    }

    /**
     * Proxy zu llm-cascade GET/PUT/DELETE /api/categories — Display-Metadaten
     * pro Kategorie (displayName, description, orderIdx). Wird vom Frontend
     * für den Inline-Edit in der Cascades-View + das Kategorie-Dropdown im
     * "Neues Modell hinzufügen"-Form genutzt.
     */
    @GetMapping("/categories")
    public com.fasterxml.jackson.databind.JsonNode categories() {
        return cascade.getCategories();
    }

    @PutMapping("/categories/{name}")
    public ResponseEntity<Map<String, Object>> categoryUpsert(@PathVariable String name,
                                                              @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        boolean ok = cascade.updateCategory(name, body);
        // Rename/Description-Edit live ans Frontend broadcasten → categoryTitles
        // (dynamisch aus /api/categories) + Bereich-Toggle aktualisieren sich.
        if (ok) sse.broadcast("category-updated", Map.of("name", name));
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                  : ResponseEntity.status(502).body(Map.of("ok", false, "error", "llm-cascade upstream failed"));
    }

    @DeleteMapping("/categories/{name}")
    public ResponseEntity<Map<String, Object>> categoryDeleteMeta(@PathVariable String name) {
        boolean ok = cascade.deleteCategoryMeta(name);
        if (ok) sse.broadcast("category-updated", Map.of("name", name, "deleted", true));
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                  : ResponseEntity.status(502).body(Map.of("ok", false, "error", "llm-cascade upstream failed"));
    }

    // ─── Provider-Server-Proxy (v0.8.0) ──────────────────────────────────────
    // Externe Inferenz-Server pro Modell. CRUD liegt in llm-cascade; hier nur
    // durchgereicht, damit <ki-provider-servers> (KI_MODELS_API_BASE='/api')
    // Server listen/anlegen/löschen kann. Das echte Routing greift erst mit
    // llm-cascade >= 0.8.0.

    @GetMapping("/provider-servers")
    public com.fasterxml.jackson.databind.JsonNode providerServers() {
        return cascade.getProviderServers();
    }

    @PutMapping("/provider-servers/{name}")
    public ResponseEntity<Map<String, Object>> providerServerUpsert(@PathVariable String name,
                                                                    @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        boolean ok = cascade.upsertProviderServer(name, body);
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                  : ResponseEntity.status(502).body(Map.of("ok", false, "error", "llm-cascade upstream failed"));
    }

    @DeleteMapping("/provider-servers/{name}")
    public ResponseEntity<Map<String, Object>> providerServerDelete(@PathVariable String name) {
        boolean ok = cascade.deleteProviderServer(name);
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                  : ResponseEntity.status(502).body(Map.of("ok", false, "error", "llm-cascade upstream failed"));
    }

    // ─── Cascade-Cooldown-Override (Tri-State, analog EduPro PR #37) ─────────

    @GetMapping("/cascade-cooldown-override")
    public Map<String, Object> getCooldownOverride() {
        ObjectNode sw = configs.getSwitcher();
        JsonNode v = sw.get("cascadeCooldownOverride");
        Map<String, Object> out = new LinkedHashMap<>();
        if (v == null || v.isNull()) {
            out.put("cooldownOverride", null);
            out.put("effective", "default");
            out.put("effectiveCooldown", true);
        } else if (v.asBoolean(true)) {
            out.put("cooldownOverride", Boolean.TRUE);
            out.put("effective", "explicit_on");
            out.put("effectiveCooldown", true);
        } else {
            out.put("cooldownOverride", Boolean.FALSE);
            out.put("effective", "explicit_off");
            out.put("effectiveCooldown", false);
        }
        return out;
    }

    public static class CooldownOverrideRequest { public Boolean cooldownOverride; }

    @PostMapping("/cascade-cooldown-override")
    public synchronized Map<String, Object> setCooldownOverride(@RequestBody CooldownOverrideRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        if (req == null || req.cooldownOverride == null) {
            sw.putNull("cascadeCooldownOverride");
        } else {
            sw.put("cascadeCooldownOverride", req.cooldownOverride);
        }
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        sse.broadcast("cooldown-override", Map.of("value", req == null || req.cooldownOverride == null ? "default"
            : req.cooldownOverride ? "explicit_on" : "explicit_off"));
        return getCooldownOverride();
    }

    // ─── Cascade-Model enable/disable (proxy zu llm-cascade PUT /api/models/{id}) ──

    public static class ModelPatchRequest {
        public Boolean enabled;
        public Boolean autoDisabled;
        public Integer cooldown503OverrideSec;
    }

    @PostMapping("/cascade-models/{id}/toggle")
    public Map<String, Object> toggleCascadeModel(@PathVariable long id, @RequestBody ModelPatchRequest req) {
        if (req == null || req.enabled == null)
            return Map.of("ok", false, "error", "enabled required");
        boolean ok = modelSvc.patchModel(id, Map.of("enabled", req.enabled)).isPresent();
        sse.broadcast("model-toggled", Map.of("id", id, "enabled", req.enabled, "ok", ok));
        return Map.of("ok", ok, "id", id, "enabled", req.enabled);
    }

    @PostMapping("/cascade-models/{id}/re-enable")
    public Map<String, Object> reEnableCascadeModel(@PathVariable long id) {
        boolean ok = modelSvc.patchModel(id,
            Map.of("autoDisabled", Boolean.FALSE, "enabled", Boolean.TRUE)).isPresent();
        sse.broadcast("model-reenabled", Map.of("id", id, "ok", ok));
        return Map.of("ok", ok, "id", id);
    }

    // ─── Cascade-Models CRUD — lokale DB (Phase K) ───────────────────────────

    @PostMapping("/cascade-models")
    public Map<String, Object> createCascadeModel(@RequestBody Map<String, Object> body) {
        try {
            AiModelConfig created = modelSvc.createModel(body);
            sse.broadcast("model-created", Map.of("ok", true, "id", created.getId()));
            return Map.of(
                "ok", true,
                "id", created.getId(),
                "provider", created.getProvider(),
                "modelId", created.getModelId()
            );
        } catch (IllegalArgumentException e) {
            // Service rejected (z.B. Duplikat oder Pflichtfeld fehlt) — Lib-konformes
            // Error-Format (HTTP 200 + ok:false), damit ki-models-ui den error-String
            // als Toast/Fehlermeldung anzeigt.
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @DeleteMapping("/cascade-models/{id}")
    public Map<String, Object> deleteCascadeModel(@PathVariable long id) {
        // Vor dem Delete: Modell-Details merken damit wir nachher entscheiden
        // können ob das gelöschte Modell der Live-aktive war.
        var deleted = modelSvc.findModelById(id);
        boolean ok = modelSvc.deleteModel(id);
        sse.broadcast("model-deleted", Map.of("id", id, "ok", ok));

        // Auto-Follow-up: war das das aktuell aktive Modell? Dann switche zum
        // ersten noch verfügbaren Modell (filter: hat Key + nicht autoDisabled).
        // Sonst bleibt cfg auf einem ins-Leere-Zeigen, was vermutlich zu einem
        // verwirrenden Banner/Status führt.
        if (ok && deleted.isPresent()) {
            autoSwitchIfActiveWasDeleted(deleted.get());
        }

        return Map.of("ok", ok, "id", id);
    }

    /**
     * Prüft ob das gelöschte Modell der aktuell aktive Live-Provider war —
     * wenn ja, switche atomar zum ersten noch verfügbaren Modell. Wenn keins
     * mehr da ist, passiert nichts (Status wird stale, UI kann das anzeigen).
     */
    private void autoSwitchIfActiveWasDeleted(com.dataclub.switcher.model.AiModelConfig deleted) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        String currentProvider = configs.deriveProvider(cfg);
        String currentModel = sw.path("activeRoute").path("model").asText(
            cfg.path("model").asText(""));

        String deletedSwProv = CASCADE_TO_SWITCHER.getOrDefault(deleted.getProvider(), deleted.getProvider());
        boolean wasActive = deletedSwProv.equals(currentProvider)
                         && (deleted.getModelId().equals(currentModel)
                             || (currentModel == null || currentModel.isEmpty()));
        if (!wasActive) return;

        // Erstes verfügbares Modell finden (Key gesetzt + nicht auto-disabled).
        com.dataclub.switcher.model.AiModelConfig replacement = null;
        for (com.dataclub.switcher.model.AiModelConfig m : modelSvc.listModels()) {
            if (Boolean.TRUE.equals(m.getAutoDisabled())) continue;
            if (!modelSvc.modelHasKey(m)) continue;
            replacement = m;
            break;
        }
        if (replacement == null) return;

        SwitchRequest req = new SwitchRequest();
        req.provider = CASCADE_TO_SWITCHER.getOrDefault(replacement.getProvider(), replacement.getProvider());
        req.model = replacement.getModelId();
        doSwitch(req); // schreibt config, Restart-Marker, broadcastet 'switch'-SSE
    }

    /**
     * Connectivity-Test bleibt Proxy zur llm-cascade — die hat die Provider-SDKs.
     * Die DB-IDs sind identisch (selbe Tabelle, beide Konsumenten lesen sie).
     */
    @PostMapping("/cascade-models/{id}/test")
    public JsonNode testCascadeModel(@PathVariable long id) {
        // Anthropic-Sonderfall: Test ruft direkt api.anthropic.com auf und braucht
        // einen echten sk-ant-Key. Max/Pro-OAuth (das für den Live-Switch reicht)
        // ist nicht für direkte API-Calls nutzbar. Kurzschluss mit klarer Message
        // statt die irreführende cascade-Fehlermeldung „Key nicht gesetzt".
        var maybeModel = modelSvc.findModelById(id);
        if (maybeModel.isPresent()) {
            var m = maybeModel.get();
            if ("anthropic".equalsIgnoreCase(m.getProvider()) && !modelSvc.modelHasRealKey(m)) {
                ObjectNode info = configs.mapper().createObjectNode();
                info.put("ok", false);
                info.put("skipped", true);
                info.put("error",
                    "Test braucht echten sk-ant-Key (api.anthropic.com-Call). "
                  + "Für reines Live-Switchen reicht der Max/Pro-Login von Claude Code — der ist nicht testbar. "
                  + "Wenn du den Test trotzdem willst: API-Key unter https://console.anthropic.com erstellen "
                  + "(separates Credits-Billing) und unten als anthropicApiKey eintragen.");
                sse.broadcast("model-tested", Map.of("id", id, "ok", false));
                return info;
            }
        }

        JsonNode r = cascade.testModel(id);
        sse.broadcast("model-tested", Map.of("id", id, "ok", r.path("ok").asBoolean(false)));
        return r;
    }

    public static class ReorderRequest { public java.util.List<Long> orderedIds; }

    @PostMapping("/cascade-models/reorder")
    public Map<String, Object> reorderCascadeModels(@RequestBody ReorderRequest req) {
        boolean ok = req != null && req.orderedIds != null && modelSvc.reorderModels(req.orderedIds);
        sse.broadcast("models-reordered", Map.of("ok", ok));
        return Map.of("ok", ok);
    }

    // ─── Generic settingKey-basierte Keys — lokale DB (Phase K) ──────────────

    /** Listet alle Settings (Werte maskiert wo sensitive). */
    @GetMapping("/cascade-settings")
    public List<Map<String, Object>> cascadeSettings() {
        return modelSvc.listSettings();
    }

    public static class CascadeSettingRequest { public String value; }

    /** Setzt ein Setting. Leerer Wert = Override entfernen. */
    @PostMapping("/cascade-settings/{key}")
    public Map<String, Object> setCascadeSetting(@PathVariable String key,
                                                 @RequestBody CascadeSettingRequest req) {
        String v = req == null ? "" : (req.value == null ? "" : req.value);
        boolean ok = modelSvc.setSetting(key, v);
        sse.broadcast("setting-updated", Map.of("key", key, "ok", ok));
        return Map.of("ok", ok, "key", key);
    }

    // ─── Library-Settings-Vertrag (ki-models-ui ≥ 0.17.0) ───────────────────
    // Die Library trifft {base}/settings (GET) + {base}/settings/{key} (POST).
    // Dünne Aliase auf die bestehenden /cascade-settings-Methoden — gleiche
    // Daten (z. B. logPromptSnippet für <ki-privacy-settings>).

    @GetMapping("/settings")
    public List<Map<String, Object>> settings() {
        return cascadeSettings();
    }

    @PostMapping("/settings/{key}")
    public Map<String, Object> setSetting(@PathVariable String key,
                                          @RequestBody CascadeSettingRequest req) {
        return setCascadeSetting(key, req);
    }

    @GetMapping("/cascade-models")
    public Map<String, Object> cascadeModels() {
        List<AiModelConfig> models = modelSvc.listModels();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("anthropic", new ArrayList<>());
        grouped.put("google", new ArrayList<>());
        grouped.put("openrouter", new ArrayList<>());
        for (AiModelConfig m : models) {
            String swProv = CASCADE_TO_SWITCHER.get(m.getProvider());
            if (swProv == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            // dbId fuer Toggle-Endpoints; modelId ist der LLM-Modell-Name
            entry.put("dbId", m.getId());
            entry.put("id", m.getModelId());
            entry.put("name", m.getDisplayName() != null ? m.getDisplayName() : m.getModelId());
            entry.put("free", false);
            entry.put("keyConfigured", modelSvc.modelHasKey(m));
            entry.put("enabled", Boolean.TRUE.equals(m.getEnabled()));
            entry.put("autoDisabled", Boolean.TRUE.equals(m.getAutoDisabled()));
            entry.put("autoDisabledReason", m.getAutoDisabledReason());
            grouped.get(swProv).add(entry);
        }
        return Map.of("source", "switcher-db", "url", cascade.url(), "grouped", grouped);
    }

    // ─── @dataclub/ki-models-ui Library-Endpoints (Phase L.4, 2026-05-14) ────
    // Die Library erwartet einen einheitlichen Vertrag (siehe README dort).
    // Die existierenden `/cascade-*`-Endpoints bleiben unverändert (Vanilla-
    // Frontend nutzt sie noch) — neu kommen `/ai-models`, `/api-keys`,
    // `/cascade-config` hinzu, die das Library-Format respektieren.

    /**
     * Flaches AiModel[]-Array (Library-Vertrag) — v0.8.1 Proxy zur Cascade
     * GET /api/models. Liefert ALLE Felder inkl. providerServerName,
     * providerBaseUrl, hardwareCompatible/-Reason, quality (gleiche geteilte DB).
     * Vorher baute der Switcher die Liste selbst und ließ die Server-/Hardware-
     * Felder weg → die Server-Spalte + das Hardware-Badge im UI hatten keine Daten.
     */
    @GetMapping("/ai-models")
    public com.fasterxml.jackson.databind.JsonNode listAiModels() {
        JsonNode all = cascade.getModels();
        if (all == null || !all.isArray()) return all;
        // Nur die Modelle des aktiven Pools — die Modell-Tabelle + Matrix/Picker
        // spiegeln den im Bereich-Toggle gewählten Pool (cloud/free/local), genau
        // wie die Cascade-Bereiche. matchesPool = dieselbe Logik wie bei /cascades.
        String pool = configs.getSwitcher().path("pool").asText("cloud");
        ArrayNode out = configs.mapper().createArrayNode();
        for (JsonNode m : all) {
            if (matchesPool(m.path("category").asText(""), pool)) out.add(m);
        }
        return out;
    }

    @PostMapping("/ai-models")
    public Map<String, Object> aiModelsCreate(@RequestBody Map<String, Object> body) {
        return createCascadeModel(body);
    }

    /**
     * v0.8.1 — Partielles Update (u.a. Server-Zuweisung `providerServerName` über
     * das Server-Dropdown). Proxy zur Cascade PUT /api/models/{id}, damit das Feld
     * persistiert UND der Auto-Provision-Trigger (Modell auf dem Server pullen)
     * greift. Der Switcher-eigene modelSvc kennt das Feld nicht.
     */
    @PutMapping("/ai-models/{id}")
    public Map<String, Object> aiModelsUpdate(@PathVariable long id, @RequestBody Map<String, Object> body) {
        boolean ok = cascade.patchModel(id, body);
        sse.broadcast("model-updated", Map.of("id", id, "ok", ok));
        return ok ? Map.of("ok", true, "id", id)
                  : Map.of("ok", false, "error", "llm-cascade upstream failed");
    }

    @DeleteMapping("/ai-models/{id}")
    public Map<String, Object> aiModelsDelete(@PathVariable long id) {
        return deleteCascadeModel(id);
    }

    @PostMapping("/ai-models/{id}/test")
    public JsonNode aiModelsTest(@PathVariable long id) {
        return testCascadeModel(id);
    }

    @PostMapping("/ai-models/{id}/toggle")
    public Map<String, Object> aiModelsToggle(@PathVariable long id, @RequestBody ModelPatchRequest req) {
        return toggleCascadeModel(id, req);
    }

    @PostMapping("/ai-models/reorder")
    public Map<String, Object> aiModelsReorder(@RequestBody ReorderRequest req) {
        return reorderCascadeModels(req);
    }

    /**
     * Library-konformer Pfad — delegiert zu /cascade-settings und remapped
     * die Items in das von `@4dataclub/ki-models-ui` erwartete Format.
     *
     * Switcher-internes Format: `{key, valueMasked, configured}`.
     * Library-Format:          `{settingKey, valueMasked, configured, keySource?, ...}`.
     *
     * Ohne dieses Remapping rendert `<ki-api-keys-section>` Items mit
     * `settingKey === undefined` und der „Speichern"-Flow lässt sich nicht
     * auf eine konkrete Zeile beziehen.
     */
    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys() {
        List<Map<String, Object>> source = cascadeSettings();
        List<Map<String, Object>> out = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            Map<String, Object> mapped = new LinkedHashMap<>(item);
            if (!mapped.containsKey("settingKey") && mapped.containsKey("key")) {
                mapped.put("settingKey", mapped.get("key"));
            }
            // Library zeigt „Quelle"-Spalte (DB/ENV/fehlt) — Switcher kennt
            // ENV-Fallback nicht im selben Sinne, also liefern wir den
            // configured-Status auf die Library-Achse gemappt.
            mapped.putIfAbsent("keySource", Boolean.TRUE.equals(mapped.get("configured")) ? "db" : "missing");
            mapped.putIfAbsent("isDefault", false);
            out.add(mapped);
        }
        return out;
    }

    @PostMapping("/api-keys/setting/{key}")
    public Map<String, Object> apiKeysSetSetting(@PathVariable String key, @RequestBody CascadeSettingRequest req) {
        return setCascadeSetting(key, req);
    }

    /** Library-konformer Pfad — delegiert zu /cascade-cooldown-override. */
    @GetMapping("/cascade-config")
    public Map<String, Object> cascadeConfigGet() {
        return getCooldownOverride();
    }

    @PutMapping("/cascade-config")
    public Map<String, Object> cascadeConfigSet(@RequestBody CooldownOverrideRequest req) {
        return setCooldownOverride(req);
    }

    // ─── Quality-Stats (Library-Component <ki-models-quality-stats> v0.12.0) ──

    /**
     * Proxy für die Quality-Stats aus llm-cascade ≥ 0.7.2.
     *
     * Library-Vertrag: {@code GET /stats/quality?sortBy=<mode>} → {@code QualityStatRow[]}
     * mit pro Modell: id, provider, modelId, displayName, category, enabled,
     * score, tier ({@code top|ok|weak|kill|unknown}), tierIcon (★/◐/▽/✗/?),
     * successRate, avgChars, callsLast30d, kill.
     *
     * Default {@code sortBy=worst-first}: KILL-Kandidaten (Score &lt; 0.1)
     * stehen oben. Switcher hat keinen eigenen llm_call_log — wir delegieren
     * komplett an die Cascade die sich die Daten aus der gemeinsam genutzten
     * DB holt. Bei Cascade unreachable: leeres Array (graceful fallback).
     */
    @GetMapping("/stats/quality")
    public JsonNode statsQuality(
            @org.springframework.web.bind.annotation.RequestParam(name = "sortBy", required = false, defaultValue = "worst-first") String sortBy) {
        return cascade.getQualityStats(sortBy);
    }

    /**
     * Performance-Stats pro Modell (Library v0.14.0 / Cascade ≥ 0.7.6).
     * Wird vom <ki-models-performance>-Component aufgerufen.
     */
    @GetMapping("/stats/performance")
    public JsonNode statsPerformance(
            @org.springframework.web.bind.annotation.RequestParam(name = "sortBy", required = false, defaultValue = "calls-desc") String sortBy) {
        return cascade.getPerformance(sortBy);
    }

    /**
     * Cooldown + Auto-Disable State pro Modell. Wird vom
     * <ki-models-cooldown-state>-Component im Auto-Refresh-Intervall
     * (30s default) aufgerufen.
     */
    @GetMapping("/cooldown-state")
    public JsonNode cooldownState() {
        return cascade.getCooldownStateList();
    }

    /**
     * Letzte Delegations-Calls für {@code <ki-delegation-live>} (Library
     * v0.17.0). Proxy zu llm-cascade GET /api/stats/calls; bei Cascade
     * unreachable liefert der Client ein leeres Array (kein Crash).
     */
    @GetMapping("/stats/calls")
    public JsonNode statsCalls() {
        return cascade.getDelegationCalls();
    }

    // ─── Shared-Analytics-Proxies (Library v0.18.0 / Cascade ≥ 0.9) ──────────

    /**
     * Erfolgs-Trend für {@code <ki-call-overview>}. Proxy zu llm-cascade
     * GET /api/stats/trend; Leer-Array bei Cascade unreachable.
     */
    @GetMapping("/stats/trend")
    public JsonNode statsTrend(
            @org.springframework.web.bind.annotation.RequestParam(name = "days", required = false, defaultValue = "30") int days) {
        return cascade.getStatsTrend(days);
    }

    /**
     * KI-Calls-Totals für {@code <ki-call-overview>}. Proxy zu llm-cascade
     * GET /api/stats/totals; Leer-Objekt bei Cascade unreachable.
     */
    @GetMapping("/stats/totals")
    public JsonNode statsTotals() {
        return cascade.getStatsTotals();
    }

    /**
     * Failover-Aufschlüsselung für {@code <ki-failover-analytics>}. Proxy zu
     * llm-cascade GET /api/stats/failover-breakdown; Leer-Objekt bei
     * Cascade unreachable.
     */
    @GetMapping("/stats/failover-breakdown")
    public JsonNode statsFailoverBreakdown() {
        return cascade.getStatsFailoverBreakdown();
    }

    // ─── Quality Auto-Disable Proxy (Library v0.12.1 / Cascade ≥ 0.7.3) ──────

    /**
     * Manueller Trigger für den Cascade-eigenen Auto-Disable-Job.
     * Wird vom Library-Component-Button {@code <ki-models-quality-stats>}
     * → "Auto-Disable jetzt" aufgerufen.
     */
    @PostMapping("/quality/run-auto-disable")
    public JsonNode qualityRunAutoDisable() {
        return cascade.runQualityAutoDisable();
    }

    /**
     * Config-Endpoint für Auto-Disable: Library nutzt das beim Mount um zu
     * entscheiden ob der Button überhaupt sichtbar gemacht wird. Bei
     * Cascade unreachable: enabled=false → Button bleibt versteckt.
     */
    @GetMapping("/quality/auto-disable-config")
    public JsonNode qualityAutoDisableConfig() {
        return cascade.getQualityAutoDisableConfig();
    }

    // ─── Preferred Category Toggle (v0.7.5) ─────────────────────────────────

    /**
     * Liest die aktuell vom User gewählte Kategorie (Switcher-UI Modus-Panel
     * „Cloud / Free"). Proxied direkt an die Cascade.
     */
    @GetMapping("/preferred-category")
    public JsonNode preferredCategory() {
        return cascade.getPreferredCategory();
    }

    /**
     * Setzt die Kategorie. Body: {@code {"category": "cloud"}} | {@code {"category": "free-only"}}
     * | {@code {"category": ""}} (Empty = zurueck zu Semantic Routing).
     */
    @PostMapping("/preferred-category")
    public Map<String, Object> setPreferredCategory(@RequestBody Map<String, Object> body) {
        String value = body == null || !(body.get("category") instanceof String s) ? "" : s;
        boolean ok = cascade.setPreferredCategory(value);
        return Map.of("ok", ok, "category", value);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String mask(String k) {
        if (k == null || k.length() < 8) return "";
        return k.substring(0, 8) + "••••••••••••••••" + k.substring(k.length() - 4);
    }
}
