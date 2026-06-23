package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
}
