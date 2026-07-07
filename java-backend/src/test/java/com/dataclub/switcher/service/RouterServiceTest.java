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
    void buildOllamaProvider_reasoningTransformer_modelListed() {
        ObjectNode p = router.buildOllamaProvider("qwen2.5-coder:7b");
        assertThat(p.path("name").asText()).isEqualTo("ollama");
        assertThat(p.path("api_base_url").asText()).contains("11434");
        // reasoning-Transformer mit enable:false löscht das thinking-Feld komplett
        // (Ollama unterstützt kein thinking → würde sonst 400 werfen). Danach streamoptions.
        var use = p.path("transformer").path("use");
        assertThat(use.get(0).get(0).asText()).isEqualTo("reasoning");
        assertThat(use.get(0).get(1).path("enable").asBoolean()).isFalse();
        assertThat(use.get(1).asText()).isEqualTo("streamoptions");
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
    void writeRouterConfig_localPool_routesViaCascadeLocalOnly_failClosed(@TempDir Path tmp) throws Exception {
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
        // Alle Pools routen über llm-cascade → serverseitiges Failover, auch local.
        // Default = llm-cascade mit LOCAL-Target (supermodel aus → "local").
        assertThat(out.path("Router").path("default").asText()).isEqualTo("llm-cascade,local");
        // FAIL-CLOSED (Verteidigung in der Tiefe):
        //  (a) KEIN Cloud-Provider im ccr-Config, egal ob Cloud-Keys da sind.
        java.util.List<String> providerNames = new java.util.ArrayList<>();
        out.path("Providers").forEach(p -> providerNames.add(p.path("name").asText()));
        assertThat(providerNames).containsExactlyInAnyOrder("llm-cascade", "ollama");
        assertThat(providerNames).doesNotContain("gemini", "openrouter", "deepseek");
        //  (b) Der llm-cascade-Provider kennt bei local NUR *-local-Targets — der
        //      ccr-Router kann keine Cloud-Kaskade ansprechen.
        for (JsonNode p : out.path("Providers")) {
            if ("llm-cascade".equals(p.path("name").asText())) {
                for (JsonNode target : p.path("models")) {
                    assertThat(target.asText()).endsWith("local");
                }
            }
        }
        // Direkt-Route (Debug) zeigt aufs lokale Modell.
        assertThat(out.path("Router").path("_directFallback").asText()).isEqualTo("ollama,qwen2.5-coder:7b");
    }

    @Test
    void writeRouterConfig_localPendingNoModel_viaCascadeLocal_noNpe(@TempDir Path tmp) throws Exception {
        // Fail-closed pending state: local pool, NO activeRoute, NO fallback_chain,
        // kein aktiviertes Modell → ollama-Direktprovider hat leere models-Liste.
        // Darf nicht in eine NPE laufen und muss trotzdem via llm-cascade (local) routen.
        Path cfgFile = tmp.resolve("router-config-pending.json");
        when(configs.routerConfigPath()).thenReturn(cfgFile.toString());

        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "local");
        sw.put("localOrchestratorPending", true);
        when(configs.getSwitcher()).thenReturn(sw);
        // Cloud keys present in DB — must be ignored (fail-closed)
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");
        when(modelSvc.getSettingRaw("openrouterApiKey")).thenReturn("sk-or-valid");

        // Must not throw NPE
        router.writeRouterConfig();

        JsonNode out = M.readTree(cfgFile.toFile());
        // Route bleibt auf llm-cascade,local — die Cascade findet lokal kein Modell
        // und failt dort (fail-closed), NIE Cloud.
        assertThat(out.path("Router").path("default").asText()).isEqualTo("llm-cascade,local");
        java.util.List<String> providerNames = new java.util.ArrayList<>();
        out.path("Providers").forEach(p -> providerNames.add(p.path("name").asText()));
        assertThat(providerNames).doesNotContain("gemini", "openrouter", "deepseek");
    }

    // ── Fix B (Modell-Treue) ──────────────────────────────────────────────────

    @Test
    void writeRouterConfig_explicitGoogleModel_pinsDirectRoute(@TempDir Path tmp) throws Exception {
        // User waehlt konkret google/gemini-2.5-pro. Der Haupt-Loop MUSS direkt auf
        // "gemini,gemini-2.5-pro" routen — nicht auf die orchestrator-Kaskade, die
        // sonst ihr eigenes Top-Modell serviert (der fruehere "waehle Opus, kriege
        // Gemini"-Bug in umgekehrter Richtung).
        Path cfgFile = tmp.resolve("router-config-pin.json");
        when(configs.routerConfigPath()).thenReturn(cfgFile.toString());
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");

        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "cloud");
        sw.put("supermodel", true);
        ObjectNode ar = sw.putObject("activeRoute");
        ar.put("provider", "google");
        ar.put("model", "gemini-2.5-pro");
        when(configs.getSwitcher()).thenReturn(sw);

        router.writeRouterConfig();

        JsonNode out = M.readTree(cfgFile.toFile());
        assertThat(out.path("Router").path("default").asText()).isEqualTo("gemini,gemini-2.5-pro");
        assertThat(out.path("Router").path("think").asText()).isEqualTo("gemini,gemini-2.5-pro");
    }

    @Test
    void writeRouterConfig_noExplicitModel_keepsOrchestratorCascade(@TempDir Path tmp) throws Exception {
        // Kein konkretes Provider-Modell (activeRoute==llm-cascade, z.B. Anthropic-
        // mit-DB-Key): der Haupt-Loop bleibt auf der orchestrator-Kaskade.
        Path cfgFile = tmp.resolve("router-config-cascade.json");
        when(configs.routerConfigPath()).thenReturn(cfgFile.toString());
        when(modelSvc.getSettingRaw("geminiApiKey")).thenReturn("AIza-valid");

        ObjectNode sw = M.createObjectNode();
        sw.put("pool", "cloud");
        sw.put("supermodel", true);
        ObjectNode ar = sw.putObject("activeRoute");
        ar.put("provider", "llm-cascade");
        ar.put("model", "cloud");
        when(configs.getSwitcher()).thenReturn(sw);

        router.writeRouterConfig();

        JsonNode out = M.readTree(cfgFile.toFile());
        assertThat(out.path("Router").path("default").asText()).isEqualTo("llm-cascade,orchestrator-cloud");
    }
}
