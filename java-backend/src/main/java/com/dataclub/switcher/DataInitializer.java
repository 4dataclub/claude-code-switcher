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
            } else {
                // Phase S' Migration (2026-05-23): Vorhandene Modelle ohne category
                // bekommen "default" zugewiesen, damit die Cascades-View sie zeigt.
                migrateCategoryNull(modelRepo);
            }
            // Settings werden nicht geseedet — Admin pflegt sie via UI.
            // (Bei Bedarf hier: API-Keys aus env-var migrieren.)
        };
    }

    /**
     * Default-Chain für frische DBs (Phase K + S').
     *
     * Cascade-Struktur (Phase S') — zwei Bereiche:
     * • "default"  — primäre Modelle (bezahlte API, hohe Qualität)
     * • "fallback" — kostenfreie Alternativen über OpenRouter
     *
     * Switcher nutzt die Bereiche zur visuellen Trennung im Admin-UI
     * ({@code <ki-cascades-view>}). Cooldown-Isolation zwischen den
     * Bereichen läuft in llm-cascade v0.4.0+.
     */
    private void seedDefaultChain(AiModelConfigRepository modelRepo) {
        LocalDateTime now = LocalDateTime.now();
        record Default(String provider, String modelId, String displayName, String settingKey, String category) {}
        List<Default> defaults = List.of(
            // ── default-Cascade: primäre Modelle ──
            new Default("gemini",     "gemini-2.5-pro",                          "Gemini 2.5 Pro",        "geminiApiKey",     "default"),
            new Default("anthropic",  "claude-sonnet-4-5-20250929",              "Claude Sonnet 4.5",     "anthropicApiKey",  "default"),
            // ── fallback-Cascade: kostenfreie OpenRouter-Modelle ──
            new Default("openrouter", "google/gemini-2.5-flash",                 "Gemini 2.5 Flash (OR)", "openrouterApiKey", "fallback"),
            new Default("openrouter", "deepseek/deepseek-chat-v3-0324:free",     "DeepSeek V3 (free)",    "openrouterApiKey", "fallback"),
            new Default("openrouter", "meta-llama/llama-3.3-70b-instruct:free",  "Llama 3.3 70B (free)",  "openrouterApiKey", "fallback")
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
                .category(d.category())
                .enabled(Boolean.TRUE)
                .orderIdx(idx++)
                .autoDisabled(Boolean.FALSE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }
        System.out.println("[seed] switcher: Default-Chain geseedet (default + fallback Cascades)");
    }

    /**
     * Phase-S'-Migration: Modelle ohne category-Wert bekommen "default".
     *
     * Idempotent — läuft bei jedem Start, ist aber nach dem ersten Lauf
     * eine No-Op (alle Modelle haben dann einen Wert).
     */
    private void migrateCategoryNull(AiModelConfigRepository modelRepo) {
        List<AiModelConfig> uncategorized = modelRepo.findAll().stream()
            .filter(m -> m.getCategory() == null || m.getCategory().isBlank())
            .toList();
        if (uncategorized.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (AiModelConfig m : uncategorized) {
            m.setCategory("default");
            m.setUpdatedAt(now);
        }
        modelRepo.saveAll(uncategorized);
        System.out.println("[migrate] switcher: " + uncategorized.size()
            + " Modelle ohne category → 'default' gesetzt (Phase S')");
    }
}
