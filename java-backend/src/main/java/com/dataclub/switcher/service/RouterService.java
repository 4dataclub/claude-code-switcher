package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Erzeugt die router-config.json fuer ccr (claude-code-router) und startet
 * den ccr-Container neu wenn die Config geaendert wurde.
 *
 * Provider-Mapping UI ↔ ccr-internal:
 *   google → gemini    (ccr-Bean-Name)
 *   anthropic → anthropic
 *   openrouter → openrouter
 */
@Service
public class RouterService {

    private final ConfigService configs;
    private final ObjectMapper mapper;
    private final DockerClient docker;

    @Value("${switcher.router.container}")
    private String routerContainer;

    public RouterService(ConfigService configs) {
        this.configs = configs;
        this.mapper = configs.mapper();
        DefaultDockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("unix:///var/run/docker.sock")
            .build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
            .dockerHost(cfg.getDockerHost())
            .build();
        this.docker = DockerClientImpl.getInstance(cfg, http);
    }

    private static final Map<String, String> UI_TO_CCR = new HashMap<>() {{
        put("google", "gemini");
        put("anthropic", "anthropic");
        put("openrouter", "openrouter");
    }};

    /** Provider-Defaults (api-base-url + Modell-Liste) — analog der alten server.js. */
    public ArrayNode buildProviders(ObjectNode keys) {
        ArrayNode out = mapper.createArrayNode();
        if (keys.has("google") && !keys.get("google").asText("").isBlank()) {
            ObjectNode p = mapper.createObjectNode();
            p.put("name", "gemini");
            p.put("api_base_url", "https://generativelanguage.googleapis.com/v1beta/models/");
            p.put("api_key", keys.get("google").asText());
            ArrayNode m = p.putArray("models");
            m.add("gemini-2.5-pro"); m.add("gemini-2.5-flash"); m.add("gemini-2.5-flash-lite");
            p.set("transformer", mapper.createObjectNode().set("use", mapper.createArrayNode().add("gemini")));
            out.add(p);
        }
        if (keys.has("openrouter") && !keys.get("openrouter").asText("").isBlank()) {
            ObjectNode p = mapper.createObjectNode();
            p.put("name", "openrouter");
            p.put("api_base_url", "https://openrouter.ai/api/v1/chat/completions");
            p.put("api_key", keys.get("openrouter").asText());
            ArrayNode m = p.putArray("models");
            for (String s : new String[]{
                "anthropic/claude-sonnet-4.5",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-pro",
                "deepseek/deepseek-chat-v3.1",
                "meta-llama/llama-3.3-70b-instruct:free",
                "openai/gpt-oss-120b:free"
            }) m.add(s);
            p.set("transformer", mapper.createObjectNode().set("use", mapper.createArrayNode().add("openrouter")));
            out.add(p);
        }
        return out;
    }

    /** Schreibt router-config.json basierend auf _switcher in der Switcher-Config. */
    public synchronized void writeRouterConfig() {
        ObjectNode sw = configs.getSwitcher();
        ObjectNode keys = sw.has("keys") && sw.get("keys").isObject()
            ? (ObjectNode) sw.get("keys") : mapper.createObjectNode();

        // aktive Route: explicit oder erstes Element der fallback_chain
        String routeProvider = null, routeModel = null;
        if (sw.has("activeRoute") && sw.get("activeRoute").isObject()) {
            routeProvider = sw.get("activeRoute").path("provider").asText(null);
            routeModel    = sw.get("activeRoute").path("model").asText(null);
        }
        if (routeProvider == null && sw.has("fallback_chain") && sw.get("fallback_chain").isArray()
                && sw.get("fallback_chain").size() > 0) {
            routeProvider = sw.get("fallback_chain").get(0).path("provider").asText(null);
            routeModel    = sw.get("fallback_chain").get(0).path("model").asText(null);
        }
        if (routeProvider == null && sw.has("fallback") && sw.get("fallback").isObject()) {
            routeProvider = sw.get("fallback").path("provider").asText(null);
            routeModel    = sw.get("fallback").path("model").asText(null);
        }

        String mappedProvider = routeProvider != null ? UI_TO_CCR.getOrDefault(routeProvider, routeProvider) : null;
        ArrayNode providers = buildProviders(keys);

        String defaultRoute = "";
        if (mappedProvider != null && routeModel != null) {
            defaultRoute = mappedProvider + "," + routeModel;
        } else if (providers.size() > 0) {
            String n = providers.get(0).get("name").asText();
            String m = providers.get(0).get("models").get(0).asText();
            defaultRoute = n + "," + m;
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("LOG", true);
        out.put("HOST", "0.0.0.0");
        out.put("PORT", 3456);
        out.put("API_TIMEOUT_MS", 600000);
        out.set("Providers", providers);
        ObjectNode router = mapper.createObjectNode();
        router.put("default", defaultRoute);
        router.put("background", defaultRoute);
        router.put("think", defaultRoute);
        router.put("longContext", defaultRoute);
        out.set("Router", router);

        try {
            Files.createDirectories(Paths.get(configs.routerConfigPath()).getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(configs.routerConfigPath()).toFile(), out);
        } catch (IOException e) {
            throw new RuntimeException("router-config.json schreiben fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /** ccr-Container neustarten + warten bis er wieder antwortet. */
    public boolean restartRouter() {
        try {
            docker.restartContainerCmd(routerContainer).withTimeout(2).exec();
            // Kurz warten — health-check via socat-port koennte hier abgefragt werden.
            // Aktueller Stand: einfach 4s warten, der ccr-Daemon braucht das.
            Thread.sleep(4000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
