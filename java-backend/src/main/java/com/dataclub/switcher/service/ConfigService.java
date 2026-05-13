package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Liest + schreibt die zwei Konfig-Dateien des Switchers:
 *  - ~/.claude/settings.json        (Switcher-State + claude-Settings)
 *  - ~/.claude/router-config.json   (ccr-Config)
 *  - ~/.claude/.switcher-restart    (Wrapper-Restart-Marker)
 *
 * Format _switcher: { provider, activeRoute, fallback_chain, chain_position,
 *                     keys{anthropic,google,openrouter}, mode, thresholds,
 *                     lastWarn, lastFailoverAt, lastAutoSwitch, lastAutoPromoteAt }
 */
@Service
public class ConfigService {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Value("${switcher.claude.config}")
    private String configPath;

    @Value("${switcher.router.config}")
    private String routerConfigPath;

    /** Default-Failover-Chain (analog server.js DEFAULT_CHAIN). */
    public ArrayNode defaultChain() {
        ArrayNode chain = mapper.createArrayNode();
        ObjectNode a = mapper.createObjectNode(); a.put("provider", "google");     a.put("model", "gemini-2.5-pro");           chain.add(a);
        ObjectNode b = mapper.createObjectNode(); b.put("provider", "openrouter"); b.put("model", "google/gemini-2.5-flash");  chain.add(b);
        return chain;
    }

    public synchronized ObjectNode readConfig() {
        File f = new File(configPath);
        if (!f.exists()) return mapper.createObjectNode();
        try {
            JsonNode n = mapper.readTree(f);
            return n.isObject() ? (ObjectNode) n : mapper.createObjectNode();
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    public synchronized void writeConfig(ObjectNode config) {
        try {
            Path p = Paths.get(configPath);
            Files.createDirectories(p.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Konnte Switcher-Config nicht schreiben: " + e.getMessage(), e);
        }
    }

    /** Wrapper-Restart-Marker — claude-auto-wrapper liest ihn und startet `claude` neu. */
    public void writeRestartMarker(String reason, ObjectNode extra) {
        try {
            ObjectNode marker = mapper.createObjectNode();
            marker.put("at", System.currentTimeMillis());
            marker.put("reason", reason);
            if (extra != null) marker.setAll(extra);
            Path p = Paths.get(configPath).getParent().resolve(".switcher-restart");
            mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), marker);
            // Legacy-Signal-Datei (alte Wrapper)
            Files.writeString(Paths.get(configPath).getParent().resolve(".restart-signal"),
                String.valueOf(System.currentTimeMillis()));
        } catch (IOException ignored) {}
    }

    /** _switcher.* Sub-Objekt holen, bei Bedarf anlegen. */
    public synchronized ObjectNode getSwitcher() {
        ObjectNode cfg = readConfig();
        JsonNode sw = cfg.get("_switcher");
        if (sw == null || !sw.isObject()) {
            ObjectNode empty = mapper.createObjectNode();
            empty.set("keys", mapper.createObjectNode());
            cfg.set("_switcher", empty);
            writeConfig(cfg);
            return empty;
        }
        return (ObjectNode) sw;
    }

    /** Aktuellen Provider aus Config ableiten — Quelle der Wahrheit ist _switcher.provider. */
    public String deriveProvider(ObjectNode config) {
        ObjectNode sw = config.has("_switcher") && config.get("_switcher").isObject()
            ? (ObjectNode) config.get("_switcher") : null;
        if (sw != null && sw.path("provider").isTextual()) return sw.get("provider").asText();
        // Fallback: aus env-Block ableiten
        JsonNode env = config.path("env");
        String baseUrl = env.path("ANTHROPIC_BASE_URL").asText("");
        if (baseUrl.contains("3456")) return "google"; // router-Pfad — default annahme
        return "anthropic";
    }

    public ObjectMapper mapper() { return mapper; }
    public String routerConfigPath() { return routerConfigPath; }
    public String configPath() { return configPath; }
}
