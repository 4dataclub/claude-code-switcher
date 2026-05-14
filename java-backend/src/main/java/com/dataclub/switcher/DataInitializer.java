package com.dataclub.switcher;

import com.dataclub.switcher.model.AiModelConfig;
import com.dataclub.switcher.model.AppSetting;
import com.dataclub.switcher.repository.AiModelConfigRepository;
import com.dataclub.switcher.repository.AppSettingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase K (2026-05-14) — Switcher Default-Seed beim ersten Start.
 *
 * Schreibt nur wenn Tabellen leer sind. llm-cascade nutzt dieselbe DB +
 * dieselbe Tabelle, also kann auch dort schon geseedet worden sein — in dem
 * Fall passiert hier nichts (idempotent).
 *
 * Default-Chain: Gemini-2.5-Pro (primary) + OpenRouter google/gemini-2.5-flash
 * (Fallback). Diese Sequenz spiegelt {@code ConfigService.defaultChain()}.
 */
@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner switcherInit(AiModelConfigRepository modelRepo,
                                   AppSettingRepository settingRepo) {
        return args -> {
            if (modelRepo.count() == 0) {
                seedDefaultChain(modelRepo);
            }
            // Settings werden nicht geseedet — Admin pflegt sie via UI.
            // (Bei Bedarf hier: API-Keys aus env-var migrieren.)
        };
    }

    private void seedDefaultChain(AiModelConfigRepository modelRepo) {
        LocalDateTime now = LocalDateTime.now();
        record Default(String provider, String modelId, String displayName, String settingKey) {}
        List<Default> defaults = List.of(
            new Default("gemini",     "gemini-2.5-pro",            "Gemini 2.5 Pro",       "geminiApiKey"),
            new Default("openrouter", "google/gemini-2.5-flash",   "Gemini 2.5 Flash (OR)","openrouterApiKey"),
            new Default("anthropic",  "claude-sonnet-4-5-20250929","Claude Sonnet 4.5",    "anthropicApiKey")
        );
        int idx = 0;
        for (Default d : defaults) {
            // Dedup falls llm-cascade dieselbe Tabelle bereits befuellt hat.
            if (modelRepo.findFirstByProviderAndModelId(d.provider(), d.modelId()).isPresent()) {
                continue;
            }
            modelRepo.save(AiModelConfig.builder()
                .provider(d.provider())
                .modelId(d.modelId())
                .displayName(d.displayName())
                .apiKeySettingKey(d.settingKey())
                .enabled(Boolean.TRUE)
                .orderIdx(idx++)
                .autoDisabled(Boolean.FALSE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }
        System.out.println("[seed] switcher: Default-Chain geseedet (" + defaults.size() + " Modelle)");
    }
}
