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
    private final SwitcherModelService modelSvc;
    private final ObjectMapper mapper;
    private final DockerClient docker;

    @Value("${switcher.router.container}")
    private String routerContainer;

    @Value("${switcher.ollama.baseUrl:http://ollama:11434/v1/chat/completions}")
    private String ollamaBaseUrl = "http://ollama:11434/v1/chat/completions";

    @Value("${llm.cascade.url:http://llm-cascade:8090}")
    private String llmCascadeUrl = "http://llm-cascade:8090";

    public RouterService(ConfigService configs, SwitcherModelService modelSvc) {
        this.configs = configs;
        this.modelSvc = modelSvc;
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
        put("ollama", "ollama");
    }};

    /** Switcher-Provider → DB-Setting-Key (app_settings, derselbe Store wie die Cascade).
     *  anthropic ist NICHT dabei: das ist OAuth/Long-Token, bleibt in settings.json. */
    private static final Map<String, String> PROVIDER_TO_SETTING = Map.of(
        "google",     "geminiApiKey",
        "openrouter", "openrouterApiKey",
        "deepseek",   "deepseekApiKey",
        "anthropic",  "anthropicApiKey"
    );

    /**
     * API-Key für einen Switcher-Provider. <b>Quelle der Wahrheit ist die geteilte DB</b>
     * ({@code app_settings}) — exakt der Store, den {@code ki-models-ui} pflegt und
     * {@code llm-cascade} + EduPro lesen.
     *
     * <p>google/openrouter/deepseek kommen ausschließlich aus der DB. Für
     * {@code anthropic} gilt: DB-Key ({@code anthropicApiKey}) hat Priorität; ist keiner
     * gesetzt, fällt der Aufruf auf {@code settings.json._switcher.keys.anthropic}
     * zurück (OAuth-Long-Token — den der Wrapper aus der Datei liest, weil er selbst
     * keinen DB-Zugriff hat). So kann der User im UI einen echten sk-ant-Key
     * hinterlegen und der nutzt Vorrang vor OAuth; ohne Key läuft OAuth wie bisher.
     */
    public String resolveKey(String provider) {
        String settingKey = PROVIDER_TO_SETTING.get(provider);
        if (settingKey != null) {
            String db = modelSvc.getSettingRaw(settingKey);
            if (db != null && !db.isBlank()) return db;
            // Für anthropic: settings.json als Fallback (OAuth-Long-Token)
            if ("anthropic".equals(provider)) {
                return configs.getSwitcher().path("keys").path("anthropic").asText("");
            }
            return "";
        }
        return configs.getSwitcher().path("keys").path(provider).asText("");
    }

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
        if (keys.has("deepseek") && !keys.get("deepseek").asText("").isBlank()) {
            // DeepSeek direkt (api.deepseek.com) — OpenAI-kompatibles Format,
            // günstiger als über OpenRouter geroutet. Cascade nutzt dieselbe
            // Base-URL im OpenAiCompatProvider (LlmProviderConfig#deepseekProvider).
            ObjectNode p = mapper.createObjectNode();
            p.put("name", "deepseek");
            p.put("api_base_url", "https://api.deepseek.com/v1/chat/completions");
            p.put("api_key", keys.get("deepseek").asText());
            ArrayNode m = p.putArray("models");
            m.add("deepseek-chat");
            m.add("deepseek-reasoner");
            p.set("transformer", mapper.createObjectNode().set("use", mapper.createArrayNode().add("openrouter")));
            out.add(p);
        }
        return out;
    }

    /**
     * ccr-Provider fuer llm-cascade. Alle Requests gehen an llm-cascade,
     * das transparentes Failover + Pool x Area Routing durchfuehrt.
     *
     * Das "model"-Feld im ccr-Router-Call wird als Routing-Target interpretiert:
     *  - supermodel=AUS: "{pool}"          z.B. "cloud"
     *  - supermodel=AN:  "orchestrator-{pool}"  z.B. "orchestrator-cloud"
     *
     * <b>Pool-spezifisch (fail-closed):</b> die models-Liste enthaelt NUR die Targets
     * des aktiven Pools. So kann der ccr-Router bei pool=local ausschliesslich
     * {@code *-local}-Targets ansprechen — selbst eine fehlgeleitete model-Angabe
     * kann die Cascade nicht auf einen Cloud-Pool schicken. Verteidigung in der Tiefe
     * zusaetzlich zur ollama-only-Konfiguration des local-Cascade-Pools.
     */
    ObjectNode buildLlmCascadeProvider(String pool) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", "llm-cascade");
        p.put("api_base_url", llmCascadeUrl + "/v1/chat/completions");
        p.put("api_key", "sk-llm-cascade");
        ArrayNode m = p.putArray("models");
        // Pool-Catch-All (supermodel=AUS) + Orchestrator (supermodel=AN)
        m.add(pool);
        m.add("orchestrator-" + pool);
        // Delegate-Areas (supermodel=AN) + edupro-Areas (supermodel=AUS, purpose-Routing)
        for (String area : new String[]{"implement","review","research","dispatch","general","dev","utility","content"}) {
            m.add(area + "-" + pool);
        }
        p.set("transformer", mapper.createObjectNode().set("use", mapper.createArrayNode().add("openrouter")));
        return p;
    }

    /** ccr-Provider für lokales Ollama (OpenAI-kompatible API, Key ist Dummy). */
    ObjectNode buildOllamaProvider(String model) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", "ollama");
        p.put("api_base_url", ollamaBaseUrl);
        p.put("api_key", "ollama"); // Ollama ignoriert den Key, ccr verlangt aber einen
        ArrayNode m = p.putArray("models");
        if (model != null && !model.isBlank()) m.add(model);
        // reasoning-Transformer mit enable:false löscht das thinking-Feld komplett aus dem Request
        // (nach Patch in router/Dockerfile). Ohne Patch würde ccr thinking:{type:"disabled"} setzen,
        // was Ollama trotzdem als thinking-Request interpretiert → 400 "does not support thinking".
        ArrayNode useArr = mapper.createArrayNode();
        ArrayNode reasoningEntry = mapper.createArrayNode();
        reasoningEntry.add("reasoning");
        reasoningEntry.add(mapper.createObjectNode().put("enable", false));
        useArr.add(reasoningEntry);
        useArr.add("streamoptions");
        p.set("transformer", mapper.createObjectNode().set("use", useArr));
        return p;
    }

    /**
     * Provider-Liste je Pool. <b>local = NUR Ollama (fail-closed)</b> — kein google/
     * openrouter, egal ob Keys da sind, nichts verlässt das interne Netz. cloud/free =
     * {@link #buildProviders} wie gehabt.
     */
    ArrayNode buildProvidersForPool(String pool, ObjectNode keys, String localModel) {
        if ("local".equals(pool)) {
            ArrayNode out = mapper.createArrayNode();
            out.add(buildOllamaProvider(localModel));
            return out;
        }
        return buildProviders(keys);
    }

    /** Steht ein ccr-Provider mit diesem Namen in der Provider-Liste? (Fix B-Guard) */
    private static boolean providerPresent(ArrayNode providers, String name) {
        if (name == null) return false;
        for (com.fasterxml.jackson.databind.JsonNode p : providers) {
            if (name.equals(p.path("name").asText(""))) return true;
        }
        return false;
    }

    /** Schreibt router-config.json basierend auf _switcher in der Switcher-Config. */
    public synchronized void writeRouterConfig() {
        ObjectNode sw = configs.getSwitcher();
        // Keys aus der DB (app_settings) — EINE Quelle, wie die Cascade. resolveKey()
        // bevorzugt die DB, fällt auf settings.json zurück. So kann ein veralteter
        // settings.json-Key den Router nicht mehr ins Leere laufen lassen.
        ObjectNode keys = mapper.createObjectNode();
        String gKey = resolveKey("google");
        String oKey = resolveKey("openrouter");
        String dKey = resolveKey("deepseek");
        if (!gKey.isBlank()) keys.put("google", gKey);
        if (!oKey.isBlank()) keys.put("openrouter", oKey);
        if (!dKey.isBlank()) keys.put("deepseek", dKey);

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

        String pool = sw.path("pool").asText("cloud");
        boolean supermodelOn = sw.path("supermodel").asBoolean(false);
        String mappedProvider = routeProvider != null ? UI_TO_CCR.getOrDefault(routeProvider, routeProvider) : null;
        ArrayNode providers = buildProvidersForPool(pool, keys, routeModel);

        // ALLE Pools routen über llm-cascade → serverseitiges Failover + Pool×Area-
        // Routing (inkl. Connection-Failover, wenn ein zugeordneter Server wegbricht).
        // Der Provider ist pool-spezifisch: bei local kennt der ccr-Router nur
        // *-local-Targets ⇒ fail-closed, kein Cloud-Ausweich. Zusätzlich enthält die
        // ccr-Config für local KEINE Cloud-Keys (buildProvidersForPool → nur ollama).
        providers.insert(0, buildLlmCascadeProvider(pool));
        String cascadeModel = supermodelOn ? ("orchestrator-" + pool) : pool;
        String defaultRoute = "llm-cascade," + cascadeModel;

        // Direktroute: das direkte Modell hinter der Cascade.
        // explicitModel = der User hat im UI ein KONKRETES Provider-Modell gewaehlt
        // (google/openrouter) — nicht bloss die llm-cascade als Route.
        String directRoute = "";
        boolean explicitModel = mappedProvider != null && routeModel != null
                && !"llm-cascade".equals(mappedProvider);
        if (explicitModel) {
            directRoute = mappedProvider + "," + routeModel;
        } else if (providers.size() > 1 && providers.get(1).path("models").size() > 0) {
            String n = providers.get(1).get("name").asText();
            String m2 = providers.get(1).get("models").get(0).asText();
            directRoute = n + "," + m2;
        }

        // ── Fix B (Modell-Treue) ──────────────────────────────────────────────
        // Wurde ein konkretes Provider-Modell explizit gewaehlt, route den
        // Haupt-Loop DIREKT auf genau dieses Modell statt auf die orchestrator-
        // Kaskade. Sonst waehlt die Cascade ihr eigenes Top-Modell der Kategorie
        // (aktuell gemini-2.5-pro) und der UI-Pick bleibt wirkungslos — genau der
        // Grund fuer "ich waehle Opus, es kommt Gemini".
        // Bedingungen: pool != local (fail-closed, dort nur ollama) UND der Provider
        // steht real in der Provider-Liste (sonst kann ccr nicht routen).
        // Trade-off: der Haupt-Loop verliert das Cascade-Failover fuer diese Route —
        // dafuer bekommt der User deterministisch das gewaehlte Modell.
        // Die Supermodell-Delegationen (implement-/review-/… -{pool}) laufen weiter
        // unabhaengig ueber die Cascade, sind davon also nicht betroffen.
        if (explicitModel && !"local".equals(pool) && providerPresent(providers, mappedProvider)) {
            defaultRoute = directRoute;
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
        // Direktroute als Kommentar-Feld fuer Debugging (kein aktiver ccr-Slot)
        if (!directRoute.isBlank()) {
            router.put("_directFallback", directRoute);
        }
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
