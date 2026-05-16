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
        String k = configs.getSwitcher().path("keys").path(provider).asText("");
        return ResponseEntity.ok(Map.of("provider", provider, "key", k));
    }

    // ─── Switch ──────────────────────────────────────────────────────────────

    public static class SwitchRequest {
        public String provider;
        public String model;
        public String anthropicKey;
        public String googleKey;
        public String openrouterKey;
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

        // Keys mit Format-Validation aktualisieren (außer __UNCHANGED__)
        for (var pair : new String[][]{{"anthropic", req.anthropicKey}, {"google", req.googleKey}, {"openrouter", req.openrouterKey}}) {
            String name = pair[0], val = pair[1];
            if (val != null && !val.isEmpty() && !"__UNCHANGED__".equals(val)) {
                if (!KEY_PATTERNS.get(name).matcher(val).find()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", name + "-Key hat falsches Format. Erwartet: " + KEY_PATTERNS.get(name).pattern()));
                }
                keys.put(name, val);
            }
        }

        boolean routerNeedsRestart = false;
        if ("anthropic".equals(req.provider)) {
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            if (req.model != null) cfg.put("model", req.model); else cfg.remove("model");
            sw.remove("activeRoute");
        } else if ("google".equals(req.provider)) {
            if (keys.path("google").asText("").isBlank())
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
            if (keys.path("openrouter").asText("").isBlank())
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

        if (!"auto".equals(sw.path("mode").asText("manual"))) {
            ObjectNode lw = configs.mapper().createObjectNode();
            lw.put("percent", 100);
            lw.put("project", req == null ? null : req.project);
            lw.put("source", "wrapper-quota-error");
            lw.put("at", System.currentTimeMillis());
            sw.set("lastWarn", lw);
            cfg.set("_switcher", sw);
            configs.writeConfig(cfg);
            return Map.of("action", "notify", "reason", "auto-mode disabled");
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
            if (keyName != null && !keys.path(keyName).asText("").isBlank()) break;
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
        ObjectNode env = cfg.has("env") && cfg.get("env").isObject() ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
        env.put("ANTHROPIC_API_KEY", "sk-ccr-anything");
        env.put("ANTHROPIC_BASE_URL", HOST_ROUTER_URL);
        cfg.put("model", "claude-sonnet-4-5-20250929");
        sw.put("provider", target.path("provider").asText());
        sw.set("activeRoute", target.deepCopy());
        sw.put("chain_position", pos + 1);
        sw.put("lastFailoverAt", System.currentTimeMillis());
        ObjectNode lastSwitch = configs.mapper().createObjectNode();
        lastSwitch.put("at", System.currentTimeMillis());
        ObjectNode from = configs.mapper().createObjectNode();
        from.put("provider", currentProvider);
        from.put("model", cfg.path("model").asText(null));
        lastSwitch.set("from", from);
        lastSwitch.set("to", target.deepCopy());
        lastSwitch.put("reason", "quota");
        sw.set("lastAutoSwitch", lastSwitch);
        cfg.set("env", env);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        router.restartRouter();
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
        AiModelConfig created = modelSvc.createModel(body);
        sse.broadcast("model-created", Map.of("ok", true, "id", created.getId()));
        return Map.of(
            "ok", true,
            "id", created.getId(),
            "provider", created.getProvider(),
            "modelId", created.getModelId()
        );
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

    /** Flaches AiModel[]-Array (Library-Vertrag) statt grouped Object. */
    @GetMapping("/ai-models")
    public List<Map<String, Object>> listAiModels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiModelConfig m : modelSvc.listModels()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.getId());
            entry.put("provider", m.getProvider());
            entry.put("modelId", m.getModelId());
            entry.put("displayName", m.getDisplayName());
            entry.put("apiKeySettingKey", m.getApiKeySettingKey());
            entry.put("enabled", Boolean.TRUE.equals(m.getEnabled()));
            entry.put("orderIdx", m.getOrderIdx());
            entry.put("cooldown503OverrideSec", m.getCooldown503OverrideSec());
            entry.put("autoDisabled", Boolean.TRUE.equals(m.getAutoDisabled()));
            entry.put("autoDisabledReason", m.getAutoDisabledReason());
            entry.put("autoDisabledAt", m.getAutoDisabledAt());
            entry.put("keyConfigured", modelSvc.modelHasKey(m));
            entry.put("cooldownRemainingSec", 0);
            out.add(entry);
        }
        return out;
    }

    @PostMapping("/ai-models")
    public Map<String, Object> aiModelsCreate(@RequestBody Map<String, Object> body) {
        return createCascadeModel(body);
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String mask(String k) {
        if (k == null || k.length() < 8) return "";
        return k.substring(0, 8) + "••••••••••••••••" + k.substring(k.length() - 4);
    }
}
