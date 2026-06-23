package com.dataclub.switcher.controller;

import com.dataclub.switcher.model.AiModelConfig;
import com.dataclub.switcher.service.ConfigService;
import com.dataclub.switcher.service.LlmCascadeClient;
import com.dataclub.switcher.service.RouterService;
import com.dataclub.switcher.service.SseService;
import com.dataclub.switcher.service.SwitcherModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regressions-Tests für den Supermodell-Failover — sichert die runtime-verifizierte
 * Logik automatisiert ab (feedback_tests_follow_code). Schwerpunkt:
 * <b>fail-closed-local</b> (Security: kein automatischer Cloud-Ausweich im Local-Pool),
 * die datengetriebene Orchestrator-Kette und die Pool-Isolation der Filter-Endpoints.
 *
 * Reine Unit-Tests mit gemockten Services (kein Spring-Context, kein I/O).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiControllerTest {

    @Mock ConfigService configs;
    @Mock RouterService router;
    @Mock SseService sse;
    @Mock LlmCascadeClient cascade;
    @Mock SwitcherModelService modelSvc;
    @InjectMocks ApiController controller;

    private final ObjectMapper M = new ObjectMapper();

    @BeforeEach
    void setup() {
        when(configs.mapper()).thenReturn(M);
    }

    // ── Helfer ────────────────────────────────────────────────────────────────
    private AiModelConfig model(String provider, String modelId, String category, boolean enabled, int orderIdx) {
        return AiModelConfig.builder()
                .provider(provider).modelId(modelId).category(category)
                .enabled(enabled).orderIdx(orderIdx).build();
    }

    private ObjectNode node(String field, String value) {
        ObjectNode n = M.createObjectNode();
        n.put(field, value);
        return n;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  matchesPoolMode — 2-Achsen-Filter (Basis von cascades + categories +
    //  ai-models + fail-closed). AUS -> nur die Plain-Pool-Kategorie;
    //  AN -> nur die Rollen-Compounds {rolle}-{pool}.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void matchesPoolMode_off_cloud_onlyPlainPool() {
        // Supermodel AUS: nur die Plain-Pool-Cascade, NIE die Rollen-Compounds.
        assertThat(ApiController.matchesPoolMode("cloud", "cloud", false)).isTrue();
        assertThat(ApiController.matchesPoolMode("implement-cloud", "cloud", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("orchestrator-cloud", "cloud", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("free", "cloud", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("local", "cloud", false)).isFalse();
    }

    @Test
    void matchesPoolMode_off_free_inclLegacyFreeOnly() {
        assertThat(ApiController.matchesPoolMode("free", "free", false)).isTrue();
        assertThat(ApiController.matchesPoolMode("free-only", "free", false)).isTrue(); // Legacy
        assertThat(ApiController.matchesPoolMode("review-free", "free", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("cloud", "free", false)).isFalse();
    }

    @Test
    void matchesPoolMode_on_cloud_onlyRoleCompounds() {
        // Supermodel AN: nur die {rolle}-{pool}-Compounds, NIE die Plain-Pool-Cascade.
        assertThat(ApiController.matchesPoolMode("implement-cloud", "cloud", true)).isTrue();
        assertThat(ApiController.matchesPoolMode("orchestrator-cloud", "cloud", true)).isTrue();
        assertThat(ApiController.matchesPoolMode("research-cloud", "cloud", true)).isTrue();
        assertThat(ApiController.matchesPoolMode("cloud", "cloud", true)).isFalse(); // Plain raus
        assertThat(ApiController.matchesPoolMode("implement-free", "cloud", true)).isFalse();
        assertThat(ApiController.matchesPoolMode("implement-local", "cloud", true)).isFalse();
    }

    @Test
    void matchesPoolMode_local_neverMatchesCloudOrFree_failClosed() {
        // fail-closed-relevant: der Local-Pool sieht NIE Cloud-/Free-Kategorien.
        assertThat(ApiController.matchesPoolMode("local", "local", false)).isTrue();
        assertThat(ApiController.matchesPoolMode("implement-local", "local", true)).isTrue();
        assertThat(ApiController.matchesPoolMode("implement-cloud", "local", true)).isFalse();
        assertThat(ApiController.matchesPoolMode("implement-free", "local", true)).isFalse();
        assertThat(ApiController.matchesPoolMode("cloud", "local", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("free", "local", false)).isFalse();
    }

    @Test
    void matchesPoolMode_blankOrNull_isFalse() {
        assertThat(ApiController.matchesPoolMode("", "cloud", false)).isFalse();
        assertThat(ApiController.matchesPoolMode(null, "cloud", false)).isFalse();
        assertThat(ApiController.matchesPoolMode("", "cloud", true)).isFalse();
        assertThat(ApiController.matchesPoolMode(null, "cloud", true)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  orchestratorFailoverChain — datengetriebene Kette aus der Zelle
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void orchestratorChain_buildsFromCell_mapsProvider() {
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0),
                model("gemini", "gemini-2.5-flash", "orchestrator-cloud", true, 1),
                model("openrouter", "deepseek/deepseek-chat-v3.1", "implement-cloud", true, 2) // andere Kategorie → ignoriert
        ));
        ArrayNode chain = controller.orchestratorFailoverChain("cloud");

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get("provider").asText()).isEqualTo("anthropic");
        assertThat(chain.get(0).get("model").asText()).isEqualTo("claude-sonnet-4-6");
        // gemini-Provider wird auf den Switcher-Provider "google" gemappt (ccr)
        assertThat(chain.get(1).get("provider").asText()).isEqualTo("google");
        assertThat(chain.get(1).get("model").asText()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void orchestratorChain_ordersByOrderIdx() {
        when(modelSvc.listModels()).thenReturn(List.of(
                model("gemini", "gemini-2.5-flash", "orchestrator-cloud", true, 5),   // höherer idx → zweiter
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 1) // niedriger idx → zuerst
        ));
        ArrayNode chain = controller.orchestratorFailoverChain("cloud");

        assertThat(chain.get(0).get("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(chain.get(1).get("model").asText()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void orchestratorChain_skipsDisabledAndLocalProviders() {
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0),
                model("gemini", "gemini-2.5-flash", "orchestrator-cloud", false, 1), // disabled → raus
                model("ollama", "qwen2.5:14b", "orchestrator-cloud", true, 2)         // lokal → kein Cloud-Failover-Ziel → raus
        ));
        ArrayNode chain = controller.orchestratorFailoverChain("cloud");

        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).get("provider").asText()).isEqualTo("anthropic");
    }

    @Test
    void orchestratorChain_emptyCell_fallsBackToSafetyNet() {
        when(modelSvc.listModels()).thenReturn(List.of()); // keine orchestrator-Modelle
        ArrayNode chain = controller.orchestratorFailoverChain("cloud");

        // leere Zelle → supermodelFailoverChain() (Sicherheitsnetz: Opus nie ganz ohne Fallback)
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).get("provider").asText()).isEqualTo("anthropic");
        assertThat(chain.get(0).get("model").asText()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void supermodelFailoverChain_hasSonnetNativeThenCloud() {
        ArrayNode chain = controller.supermodelFailoverChain();

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).get("provider").asText()).isEqualTo("anthropic"); // Sonnet nativ zuerst
        assertThat(chain.get(0).get("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(chain.get(1).get("provider").asText()).isEqualTo("google");
        assertThat(chain.get(2).get("provider").asText()).isEqualTo("google");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  orchestratorTopModel — oberstes aktiviertes Modell der Zelle (Session-Ziel)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void topModel_picksLowestOrderIdxEnabled() {
        when(modelSvc.listModels()).thenReturn(List.of(
                model("gemini", "gemini-2.5-flash", "orchestrator-cloud", true, 5),
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 1)
        ));
        AiModelConfig top = controller.orchestratorTopModel("cloud");
        assertThat(top).isNotNull();
        assertThat(top.getModelId()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void topModel_skipsDisabled() {
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-opus-4-7", "orchestrator-cloud", false, 0), // disabled → übersprungen
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 1)
        ));
        assertThat(controller.orchestratorTopModel("cloud").getModelId()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void topModel_nullWhenEmpty() {
        when(modelSvc.listModels()).thenReturn(List.of());
        assertThat(controller.orchestratorTopModel("cloud")).isNull();
    }

    @Test
    void topModel_local_returnsOllama_notSkipped() {
        // Anders als die Failover-Kette: local/ollama ist hier ein gültiges Session-Ziel.
        when(modelSvc.listModels()).thenReturn(List.of(
                model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
        ));
        AiModelConfig top = controller.orchestratorTopModel("local");
        assertThat(top).isNotNull();
        assertThat(top.getProvider()).isEqualTo("ollama");
        assertThat(top.getModelId()).isEqualTo("qwen2.5-coder:7b");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  setMode — pool-bewusst (Local = manual/fail-closed, Cloud = auto + Kette)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void setMode_cloud_anthropicTop_pinsSessionToThatModel_direct() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0)
        ));
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "cloud"; req.supermodel = true;
        controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        // Session-Modell == Cascade-Top (vorher: Modell entfernt → claude-Binary-Default opus)
        assertThat(cfg.path("model").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(sw.path("provider").asText()).isEqualTo("anthropic");
        assertThat(sw.has("activeRoute")).isFalse();            // anthropic = direkt, kein Router
        assertThat(cfg.path("env").has("ANTHROPIC_BASE_URL")).isFalse();
    }

    @Test
    void setMode_cloud_googleTop_pinsSessionViaRouter() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("gemini", "gemini-2.5-pro", "orchestrator-cloud", true, 0)
        ));
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "cloud"; req.supermodel = true;
        controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        assertThat(cfg.path("env").path("ANTHROPIC_BASE_URL").asText()).isEqualTo("http://localhost:3456");
        assertThat(sw.path("provider").asText()).isEqualTo("google");
        assertThat(sw.path("activeRoute").path("provider").asText()).isEqualTo("google");
        assertThat(sw.path("activeRoute").path("model").asText()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void setMode_localSupermodel_isManual_noCloudChainArmed_failClosed() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of()); // kein lokales Modell aktiv

        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "local";
        req.supermodel = true;
        controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        assertThat(sw.get("pool").asText()).isEqualTo("local");
        assertThat(sw.get("supermodel").asBoolean()).isTrue();
        assertThat(sw.get("mode").asText()).isEqualTo("manual"); // KEIN auto im Local-Pool
        assertThat(sw.has("fallback_chain")).isFalse();          // KEINE Cloud-Kette scharf gestellt
        assertThat(sw.path("localOrchestratorPending").asBoolean()).isTrue();
    }

    @Test
    void setMode_local_withEnabledModel_pinsSessionViaOllama_restart() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
        ));
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "local"; req.supermodel = true;
        var resp = controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        // Opus verschwindet: Session läuft über ccr→Ollama auf dem lokalen Modell.
        assertThat(sw.path("activeRoute").path("provider").asText()).isEqualTo("ollama");
        assertThat(sw.path("activeRoute").path("model").asText()).isEqualTo("qwen2.5-coder:7b");
        assertThat(cfg.path("env").path("ANTHROPIC_BASE_URL").asText()).isEqualTo("http://localhost:3456");
        assertThat(sw.path("localOrchestratorPending").asBoolean(false)).isFalse();
        assertThat(sw.path("mode").asText()).isEqualTo("manual"); // local bleibt fail-closed, kein auto
    }

    @Test
    void setMode_local_noEnabledModel_failClosed_noReroute_pending() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of()); // kein lokales Modell aktiv
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "local"; req.supermodel = true;
        controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        assertThat(sw.path("localOrchestratorPending").asBoolean()).isTrue();
        assertThat(sw.has("activeRoute")).isFalse();               // KEIN Reroute
        assertThat(cfg.path("env").has("ANTHROPIC_BASE_URL")).isFalse(); // kein Cloud/Router-Ausweich
    }

    @Test
    void setMode_cloudSupermodel_isAuto_armsChainFromCell() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0),
                model("gemini", "gemini-2.5-flash", "orchestrator-cloud", true, 1)
        ));

        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "cloud";
        req.supermodel = true;
        controller.setMode(req);

        ObjectNode sw = (ObjectNode) cfg.get("_switcher");
        assertThat(sw.get("mode").asText()).isEqualTo("auto");
        assertThat(sw.get("chain_position").asInt()).isEqualTo(0);
        ArrayNode chain = (ArrayNode) sw.get("fallback_chain");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get("provider").asText()).isEqualTo("anthropic");
        assertThat(chain.get(1).get("provider").asText()).isEqualTo("google");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  quotaError — fail-closed-Guard (Security-Kern) + Cloud-Auto-Vorrücken
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void quotaError_localPool_neverSwitches_evenIfAuto_failClosed() {
        ObjectNode cfg = M.createObjectNode();
        ObjectNode sw = cfg.putObject("_switcher");
        sw.put("pool", "local");
        sw.put("mode", "auto");       // selbst wenn mode irgendwie auto ist: Local MUSS notify bleiben
        sw.put("provider", "anthropic");
        when(configs.readConfig()).thenReturn(cfg);

        Map<String, Object> res = controller.quotaError(new ApiController.QuotaErrorRequest());

        assertThat(res.get("action")).isEqualTo("notify");
        assertThat((String) res.get("reason")).contains("local");
        // kein Cloud-Ausweich: Provider unverändert, keine ccr-Route gesetzt, kein Router-Restart
        assertThat(sw.path("provider").asText()).isEqualTo("anthropic");
        assertThat(sw.has("activeRoute")).isFalse();
        verify(router, never()).restartRouter();
    }

    @Test
    void quotaError_manualMode_notifyOnly() {
        ObjectNode cfg = M.createObjectNode();
        ObjectNode sw = cfg.putObject("_switcher");
        sw.put("pool", "cloud");
        sw.put("mode", "manual");
        when(configs.readConfig()).thenReturn(cfg);

        Map<String, Object> res = controller.quotaError(new ApiController.QuotaErrorRequest());

        assertThat(res.get("action")).isEqualTo("notify");
        assertThat((String) res.get("reason")).contains("auto-mode disabled");
        verify(router, never()).restartRouter();
    }

    @Test
    void quotaError_cloudAuto_advancesToNativeSonnetFirst() {
        ObjectNode cfg = M.createObjectNode();
        ObjectNode sw = cfg.putObject("_switcher");
        sw.put("pool", "cloud");
        sw.put("mode", "auto");
        sw.put("provider", "anthropic");
        sw.put("chain_position", 0);
        ArrayNode chain = sw.putArray("fallback_chain");
        ObjectNode s = chain.addObject();
        s.put("provider", "anthropic");
        s.put("model", "claude-sonnet-4-6");
        ObjectNode g = chain.addObject();
        g.put("provider", "google");
        g.put("model", "gemini-2.5-flash");
        when(configs.readConfig()).thenReturn(cfg);
        when(configs.deriveProvider(cfg)).thenReturn("anthropic");

        Map<String, Object> res = controller.quotaError(new ApiController.QuotaErrorRequest());

        assertThat(res.get("action")).isEqualTo("switch");
        JsonNode target = (JsonNode) res.get("target");
        assertThat(target.get("provider").asText()).isEqualTo("anthropic"); // Sonnet nativ zuerst
        assertThat(target.get("model").asText()).isEqualTo("claude-sonnet-4-6");
        // nativ → keine ccr-Route, kein Router-Restart
        assertThat(sw.has("activeRoute")).isFalse();
        verify(router, never()).restartRouter();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  cascades + ai-models — Endpoints filtern auf den aktiven Pool
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void listAiModels_filtersToActivePool() {
        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "local");
        sw.put("supermodel", true); // Compound-Kategorien matchen nur bei Supermodell=AN
        when(configs.getSwitcher()).thenReturn(sw);
        ArrayNode all = M.createArrayNode();
        all.add(node("category", "implement-cloud"));
        all.add(node("category", "implement-local"));
        all.add(node("category", "dispatch-free"));
        when(cascade.getModels()).thenReturn(all);

        JsonNode out = controller.listAiModels();

        assertThat(out.isArray()).isTrue();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("category").asText()).isEqualTo("implement-local");
    }

    @Test
    void cascades_filtersToActivePool() {
        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "free");
        sw.put("supermodel", true); // Compound-Kategorien matchen nur bei Supermodell=AN
        when(configs.getSwitcher()).thenReturn(sw);
        ArrayNode all = M.createArrayNode();
        all.add(node("name", "implement-cloud"));
        all.add(node("name", "implement-free"));
        all.add(node("name", "review-free"));
        when(cascade.getCascades()).thenReturn(all);

        JsonNode out = controller.cascades();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("name").asText()).isEqualTo("implement-free");
        assertThat(out.get(1).get("name").asText()).isEqualTo("review-free");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  setMode — Router-Restart-Wiring (Task 6)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void setMode_routedSession_restartsRouter() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("ollama", "qwen2.5-coder:7b", "orchestrator-local", true, 0)
        ));
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "local"; req.supermodel = true;
        controller.setMode(req);

        verify(router).restartRouter(); // ccr muss die neue (ollama-only) Config laden
    }

    @Test
    void setMode_anthropicDirect_doesNotRestartRouter() {
        ObjectNode cfg = M.createObjectNode();
        when(configs.readConfig()).thenReturn(cfg);
        when(modelSvc.listModels()).thenReturn(List.of(
                model("anthropic", "claude-sonnet-4-6", "orchestrator-cloud", true, 0)
        ));
        ApiController.ModeRequest req = new ApiController.ModeRequest();
        req.pool = "cloud"; req.supermodel = true;
        controller.setMode(req);

        verify(router, never()).restartRouter(); // direkt = kein Router im Spiel
    }

    // ════════════════════════════════════════════════════════════════════════
    //  whoami — Ollama-Zweig (Task 7)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void whoami_localOllamaRoute_reportsLocalModel_notAnthropic() {
        ObjectNode cfg = M.createObjectNode();
        cfg.put("model", "claude-sonnet-4-5-20250929"); // Router-Platzhalter (darf NICHT durchschlagen)
        ObjectNode sw = cfg.putObject("_switcher");
        sw.put("provider", "ollama");
        sw.putObject("activeRoute").put("provider", "ollama").put("model", "qwen2.5-coder:7b");
        when(configs.readConfig()).thenReturn(cfg);
        when(configs.deriveProvider(cfg)).thenReturn("ollama");

        String who = controller.whoami();
        assertThat(who).contains("qwen2.5-coder:7b");
        assertThat(who).doesNotContain("Anthropic direkt");
        assertThat(who.toLowerCase()).contains("lokal"); // local/Ollama klar erkennbar
    }
}
