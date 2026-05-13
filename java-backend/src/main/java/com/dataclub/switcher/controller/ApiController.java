package com.dataclub.switcher.controller;

import com.dataclub.switcher.service.ConfigService;
import com.dataclub.switcher.service.LlmCascadeClient;
import com.dataclub.switcher.service.RouterService;
import com.dataclub.switcher.service.SseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * HTTP-API des Switchers — Java-Rewrite des frueheren Node-server.js.
 *
 * Kern-Endpoints (Phase E.2):
 *   GET  /api/status            — aktueller Provider/Modell + Verfuegbarkeits-State
 *   GET  /api/whoami            — aktive Modell-Identitaet (Plain-Text-Antwort)
 *   POST /api/switch            — Provider/Modell wechseln, ccr-Restart triggern
 *   GET  /api/auto              — Auto-Failover-Mode (an/aus)
 *   POST /api/auto              — Auto-Failover-Mode setzen
 *   GET  /api/events            — SSE-Stream fuer UI-Live-Updates
 *   GET  /api/cascade-models    — Modell-Liste aus llm-cascade (grouped by provider)
 *   GET  /api/cascade-health    — llm-cascade-Reachability-Check
 *
 * Noch nicht implementiert (Phase E.6):
 *   /api/banner, /api/warn, /api/quota-error, /api/chain-reset, /api/chain-promote,
 *   /api/recheck-now, /api/restart, /api/key/:provider
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired private ConfigService configs;
    @Autowired private RouterService router;
    @Autowired private SseService sse;
    @Autowired private LlmCascadeClient cascade;

    private static final Map<String, String> CASCADE_TO_SWITCHER = Map.of(
        "gemini", "google", "anthropic", "anthropic", "openrouter", "openrouter"
    );

    // ─── Status ───────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> status() {
        ObjectNode sw = configs.getSwitcher();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", sw.path("provider").asText(null));
        out.put("activeRoute", sw.has("activeRoute") ? sw.get("activeRoute") : null);
        out.put("autoMode", sw.path("autoMode").asBoolean(false));
        out.put("fallbackChain", sw.has("fallback_chain") ? sw.get("fallback_chain") : null);
        out.put("keysConfigured", keysConfiguredMap(sw));
        return out;
    }

    @GetMapping(value = "/whoami", produces = MediaType.TEXT_PLAIN_VALUE)
    public String whoami() {
        ObjectNode sw = configs.getSwitcher();
        String p = sw.path("provider").asText("?");
        String m = sw.path("activeRoute").path("model").asText(
                  sw.path("fallback").path("model").asText("?"));
        return p + " · " + m;
    }

    // ─── Switch ───────────────────────────────────────────────────────────────

    public static class SwitchRequest {
        public String provider;
        public String model;
        public Map<String, String> keys;
    }

    @PostMapping("/switch")
    public Map<String, Object> doSwitch(@RequestBody SwitchRequest req) {
        if (req == null || req.provider == null || req.model == null) {
            return Map.of("ok", false, "error", "provider + model erforderlich");
        }
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();

        ObjectNode route = configs.mapper().createObjectNode();
        route.put("provider", req.provider);
        route.put("model", req.model);
        sw.set("activeRoute", route);
        sw.put("provider", req.provider);

        if (req.keys != null) {
            ObjectNode keys = sw.has("keys") && sw.get("keys").isObject()
                ? (ObjectNode) sw.get("keys") : configs.mapper().createObjectNode();
            for (var e : req.keys.entrySet()) keys.put(e.getKey(), e.getValue());
            sw.set("keys", keys);
        }

        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        router.writeRouterConfig();
        boolean ok = router.restartRouter();

        sse.broadcast("switch", Map.of("provider", req.provider, "model", req.model, "ok", ok));
        return Map.of("ok", ok, "provider", req.provider, "model", req.model);
    }

    // ─── Auto-Failover-Mode ───────────────────────────────────────────────────

    @GetMapping("/auto")
    public Map<String, Object> getAuto() {
        return Map.of("autoMode", configs.getSwitcher().path("autoMode").asBoolean(false));
    }

    public static class AutoRequest { public Boolean autoMode; }

    @PostMapping("/auto")
    public Map<String, Object> setAuto(@RequestBody AutoRequest req) {
        ObjectNode cfg = configs.readConfig();
        ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
            ? (ObjectNode) cfg.get("_switcher") : configs.mapper().createObjectNode();
        boolean next = req != null && Boolean.TRUE.equals(req.autoMode);
        sw.put("autoMode", next);
        cfg.set("_switcher", sw);
        configs.writeConfig(cfg);
        sse.broadcast("auto", Map.of("autoMode", next));
        return Map.of("ok", true, "autoMode", next);
    }

    // ─── SSE ──────────────────────────────────────────────────────────────────

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sse.register();
    }

    // ─── Cascade-Proxy ────────────────────────────────────────────────────────

    @GetMapping("/cascade-health")
    public Map<String, Object> cascadeHealth() {
        return Map.of("ok", cascade.isHealthy(), "url", cascade.url());
    }

    @GetMapping("/cascade-models")
    public Map<String, Object> cascadeModels() {
        JsonNode models = cascade.getModels();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("anthropic", new ArrayList<>());
        grouped.put("google",    new ArrayList<>());
        grouped.put("openrouter",new ArrayList<>());

        if (models.isArray()) {
            for (JsonNode m : models) {
                if (!m.path("enabled").asBoolean(true)) continue;
                if (m.path("autoDisabled").asBoolean(false)) continue;
                String cascProv = m.path("provider").asText();
                String swProv = CASCADE_TO_SWITCHER.get(cascProv);
                if (swProv == null) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", m.path("modelId").asText());
                entry.put("name", m.path("displayName").asText(m.path("modelId").asText()));
                entry.put("free", false);
                entry.put("keyConfigured", m.path("keyConfigured").asBoolean(false));
                grouped.get(swProv).add(entry);
            }
        }
        return Map.of("source", "llm-cascade", "url", cascade.url(), "grouped", grouped);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Boolean> keysConfiguredMap(ObjectNode sw) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        ObjectNode keys = sw.has("keys") && sw.get("keys").isObject() ? (ObjectNode) sw.get("keys") : null;
        for (String p : new String[]{"google", "anthropic", "openrouter"}) {
            out.put(p, keys != null && !keys.path(p).asText("").isBlank());
        }
        return out;
    }
}
