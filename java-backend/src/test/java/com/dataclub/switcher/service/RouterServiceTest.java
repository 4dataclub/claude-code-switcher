package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Sichert die <b>Single-Source-Key-Auflösung</b>: der ccr-Router zieht
 * google/openrouter aus der DB ({@code app_settings} — derselbe Store wie die
 * Cascade), Fallback auf {@code settings.json _switcher.keys}. Verhindert die
 * Divergenz, die einen toten settings.json-Key den Router brechen ließ
 * (Gemini „API key not valid", obwohl in der DB ein gültiger Key lag).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterServiceTest {

    @Mock ConfigService configs;
    @Mock SwitcherModelService modelSvc;
    private RouterService router;
    private final ObjectMapper M = new ObjectMapper();

    @BeforeEach
    void setup() {
        when(configs.mapper()).thenReturn(M);
        router = new RouterService(configs, modelSvc);
    }

    @Test
    void resolveKey_prefersDbOverStaleSettingsJson() {
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AQ.Ab8-valid-db-key");
        ObjectNode sw = M.createObjectNode();
        sw.putObject("keys").put("google", "AIza-old-stale-dead"); // alter toter Key
        when(configs.getSwitcher()).thenReturn(sw);

        // DB gewinnt — der tote settings.json-Key wird ignoriert.
        assertThat(router.resolveKey("google")).isEqualTo("AQ.Ab8-valid-db-key");
    }

    @Test
    void resolveKey_googleOpenrouter_dbOnly_noSettingsJsonFallback() {
        // DB leer, aber settings.json hätte einen Legacy-Key → trotzdem leer.
        // KEIN Fallback — sonst wäre das wieder ein switcher-eigener Key-Ort (Divergenz).
        when(modelSvc.getSettingRaw("openrouterApiKey")).thenReturn(null);
        ObjectNode sw = M.createObjectNode();
        sw.putObject("keys").put("openrouter", "sk-or-legacy-settings");
        when(configs.getSwitcher()).thenReturn(sw);

        assertThat(router.resolveKey("openrouter")).isEmpty();
    }

    @Test
    void resolveKey_anthropicAlwaysFromSettingsJson() {
        // anthropic ist NICHT in PROVIDER_TO_SETTING → OAuth/Long-Token aus settings.json,
        // die DB wird gar nicht erst gefragt.
        ObjectNode sw = M.createObjectNode();
        sw.putObject("keys").put("anthropic", "sk-ant-oat01-oauth");
        when(configs.getSwitcher()).thenReturn(sw);

        assertThat(router.resolveKey("anthropic")).isEqualTo("sk-ant-oat01-oauth");
    }

    @Test
    void resolveKey_blankWhenNeitherSet() {
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("");
        when(configs.getSwitcher()).thenReturn(M.createObjectNode());

        assertThat(router.resolveKey("google")).isEmpty();
    }

    @Test
    void buildOllamaProvider_openaiTransformer_modelListed() {
        ObjectNode p = router.buildOllamaProvider("qwen2.5-coder:7b");
        assertThat(p.path("name").asText()).isEqualTo("ollama");
        assertThat(p.path("api_base_url").asText()).contains("11434");
        assertThat(p.path("transformer").path("use").get(0).asText()).isEqualTo("openai");
        assertThat(p.path("models").get(0).asText()).isEqualTo("qwen2.5-coder:7b");
    }

    @Test
    void buildProvidersForPool_local_onlyOllama_ignoresCloudKeys_failClosed() {
        ObjectNode keys = M.createObjectNode();
        keys.put("google", "AIza-valid"); keys.put("openrouter", "sk-or-valid");
        var providers = router.buildProvidersForPool("local", keys, "qwen2.5-coder:7b");
        // FAIL-CLOSED: trotz Cloud-Keys NUR Ollama, kein gemini/openrouter.
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).path("name").asText()).isEqualTo("ollama");
    }

    @Test
    void buildProvidersForPool_cloud_usesCloudProviders() {
        ObjectNode keys = M.createObjectNode();
        keys.put("google", "AIza-valid");
        var providers = router.buildProvidersForPool("cloud", keys, null);
        assertThat(providers.get(0).path("name").asText()).isEqualTo("gemini");
    }

    @Test
    void writeRouterConfig_localPool_writesOnlyOllamaRoute(@TempDir Path tmp) throws Exception {
        Path cfgFile = tmp.resolve("router-config.json");
        when(configs.routerConfigPath()).thenReturn(cfgFile.toString());
        // Local-Pool + Route auf das lokale Modell, dazu vorhandene Cloud-Keys in der DB.
        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "local");
        sw.putObject("activeRoute").put("provider", "ollama").put("model", "qwen2.5-coder:7b");
        when(configs.getSwitcher()).thenReturn(sw);
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");
        when(modelSvc.getSettingRaw("openrouterApiKey")).thenReturn("sk-or-valid");

        router.writeRouterConfig();

        JsonNode out = M.readTree(cfgFile.toFile());
        // FAIL-CLOSED: genau ein Provider, und der ist Ollama — kein gemini/openrouter.
        assertThat(out.path("Providers")).hasSize(1);
        assertThat(out.path("Providers").get(0).path("name").asText()).isEqualTo("ollama");
        assertThat(out.path("Router").path("default").asText()).isEqualTo("ollama,qwen2.5-coder:7b");
    }

    @Test
    void writeRouterConfig_localPendingNoModel_ollamaOnly_emptyDefaultRoute_noNpe(@TempDir Path tmp) throws Exception {
        // Fail-closed pending state: local pool, NO activeRoute, NO fallback_chain.
        // buildOllamaProvider(null) → models array is EMPTY.
        // Previously caused NPE: providers.get(0).get("models").get(0).asText() → get(0) == null.
        Path cfgFile = tmp.resolve("router-config-pending.json");
        when(configs.routerConfigPath()).thenReturn(cfgFile.toString());

        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "local");
        sw.put("localOrchestratorPending", true);
        // NO activeRoute, NO fallback_chain → routeModel == null → buildOllamaProvider(null) → empty models
        when(configs.getSwitcher()).thenReturn(sw);
        // Cloud keys present in DB — must be ignored (fail-closed)
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");
        when(modelSvc.getSettingRaw("openrouterApiKey")).thenReturn("sk-or-valid");

        // Must not throw NPE
        router.writeRouterConfig();

        JsonNode out = M.readTree(cfgFile.toFile());
        // Exactly one provider: ollama with empty models array
        assertThat(out.path("Providers")).hasSize(1);
        assertThat(out.path("Providers").get(0).path("name").asText()).isEqualTo("ollama");
        assertThat(out.path("Providers").get(0).path("models")).isEmpty();
        // Default route must be empty (no model → no valid route → fail-closed dead-end)
        assertThat(out.path("Router").path("default").asText()).isEmpty();
    }
}
