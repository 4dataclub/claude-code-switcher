package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Auto-Promote: alle 30 Min pruefen ob Anthropic wieder versucht werden soll.
 * Aktiv nur wenn mode=auto + chain_position>0 + lastFailoverAt > 30min her.
 */
@Service
public class AutoPromoteService {

    private static final long INTERVAL_MS = 30L * 60_000L;
    private static final long COOLDOWN_MS = 30L * 60_000L;

    @Autowired private ConfigService configs;
    @Autowired private RouterService router;
    @Autowired private SseService sse;

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INTERVAL_MS)
    public synchronized void tick() {
        try {
            ObjectNode cfg = configs.readConfig();
            ObjectNode sw = cfg.has("_switcher") && cfg.get("_switcher").isObject()
                ? (ObjectNode) cfg.get("_switcher") : null;
            if (sw == null) return;
            if (!"auto".equals(sw.path("mode").asText("manual"))) return;
            int pos = sw.path("chain_position").asInt(0);
            if (pos == 0) return;
            long lastFailover = sw.path("lastFailoverAt").asLong(0);
            if (lastFailover == 0) return;
            long age = System.currentTimeMillis() - lastFailover;
            if (age < COOLDOWN_MS) return;

            ObjectNode env = cfg.has("env") && cfg.get("env").isObject() ? (ObjectNode) cfg.get("env") : configs.mapper().createObjectNode();
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            cfg.remove("model");
            sw.put("provider", "anthropic");
            sw.put("chain_position", 0);
            sw.putNull("lastFailoverAt");
            sw.put("lastAutoPromoteAt", System.currentTimeMillis());
            sw.remove("activeRoute");
            cfg.set("env", env);
            cfg.set("_switcher", sw);
            configs.writeConfig(cfg);
            router.writeRouterConfig();

            ObjectNode marker = configs.mapper().createObjectNode();
            marker.put("hoursSinceFailover", Math.round(age / 3600000.0 * 10) / 10.0);
            configs.writeRestartMarker("auto-recheck", marker);
            sse.broadcast("auto-promoted", java.util.Map.of(
                "reason", "cooldown-elapsed",
                "hoursSinceFailover", Math.round(age / 3600000.0 * 10) / 10.0));
        } catch (Exception ignored) {}
    }
}
