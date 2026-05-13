package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Liest + schreibt die zwei Konfig-Dateien des Switchers:
 *  - ~/.claude/settings.json        (Switcher-State: Provider, Modell, Keys)
 *  - ~/.claude/router-config.json   (ccr-Config: aktive Provider-Liste, Default-Route)
 *
 * Pfade kommen aus application.properties / env-Vars.
 *
 * Format des Switcher-Configs:
 * {
 *   "_switcher": {
 *     "provider":      "google",                   // UI-Provider-Name
 *     "fallback":      { "provider": "google", "model": "gemini-2.5-pro" },
 *     "fallback_chain":[ {provider, model}, ... ],
 *     "activeRoute":   { "provider": "google", "model": "gemini-2.5-pro" },
 *     "keys": { "google": "AIza...", "anthropic": "sk-...", "openrouter": "sk-or-..." },
 *     "autoMode":      true
 *   }
 * }
 */
@Service
public class ConfigService {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Value("${switcher.claude.config}")
    private String configPath;

    @Value("${switcher.router.config}")
    private String routerConfigPath;

    public synchronized ObjectNode readConfig() {
        File f = new File(configPath);
        if (!f.exists()) return mapper.createObjectNode();
        try {
            return (ObjectNode) mapper.readTree(f);
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

    /** _switcher.* Sub-Objekt holen, bei Bedarf anlegen. */
    public synchronized ObjectNode getSwitcher() {
        ObjectNode cfg = readConfig();
        JsonNode sw = cfg.get("_switcher");
        if (sw == null || !sw.isObject()) {
            ObjectNode empty = mapper.createObjectNode();
            cfg.set("_switcher", empty);
            writeConfig(cfg);
            return empty;
        }
        return (ObjectNode) sw;
    }

    public ObjectMapper mapper() { return mapper; }

    public String routerConfigPath() { return routerConfigPath; }
}
