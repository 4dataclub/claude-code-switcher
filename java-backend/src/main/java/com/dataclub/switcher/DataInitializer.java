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
 * Phase S'' (2026-05-24) — Kategorie-Umbenennung: default→cloud, fallback→free-only.
 *
 * Schreibt nur wenn Tabellen leer sind. llm-cascade nutzt dieselbe DB +
 * dieselbe Tabelle, also kann auch dort schon geseedet worden sein — in dem
 * Fall passiert hier nichts (idempotent).
 *
 * Cascade-Struktur (Phase S''):
 *
 * ┌─ free-only ──────┐   ┌─ cloud ────────┐
 * │ deepseek-free    │   │ claude-opus    │
 * │ llama-3.3        │   │ gpt-oss-120b   │
 * │ gemma3           │   │ gemini-pro     │
 * │ cooldown: 0s     │   │ cooldown: 32s  │ ← unabhängig voneinander
 * └──────────────────┘   └────────────────┘
 *
 * "cloud"     — bezahlte Tier-Modelle (eigener Cooldown, hohe Qualität)
 * "free-only" — kostenfreie OpenRouter-Modelle (kein Cooldown, Rate-Limited)
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
                // bekommen "cloud" zugewiesen, damit die Cascades-View sie zeigt.
                migrateCategoryNull(modelRepo);
                // Phase S'' Migration (2026-05-24): Alte Kategorienamen umbenennen.
                // default → cloud, fallback → free-only (idempotent).
                migrateOldCategoryNames(modelRepo);
            }
            // Settings werden nicht geseedet — Admin pflegt sie via UI.
            // (Bei Bedarf hier: API-Keys aus env-var migrieren.)
        };
    }

    /**
     * Default-Chain für frische DBs (Phase K + S').
     *
     * Cascade-Struktur (Phase S'') — zwei Bereiche:
     * • "cloud"     — bezahlte Tier-Modelle (Anthropic, Google, OpenRouter paid)
     * • "free-only" — kostenfreie OpenRouter-Modelle (kein Cooldown nötig)
     *
     * Switcher nutzt die Bereiche zur visuellen Trennung im Admin-UI
     * ({@code <ki-cascades-view>}). Cooldown-Isolation zwischen den
     * Bereichen läuft in llm-cascade v0.4.0+.
     */
    private void seedDefaultChain(AiModelConfigRepository modelRepo) {
        LocalDateTime now = LocalDateTime.now();
        record Default(String provider, String modelId, String displayName, String settingKey, String category) {}
        List<Default> defaults = List.of(
            // ── cloud-Cascade: bezahlte Tier-Modelle (eigener Cooldown) ──
            new Default("anthropic",  "claude-opus-4-7",                          "Claude Opus 4.7",       "anthropicApiKey",  "cloud"),
            new Default("gemini",     "gemini-2.5-pro",                           "Gemini 2.5 Pro",        "geminiApiKey",     "cloud"),
            new Default("openrouter", "openai/gpt-oss-120b:free",                 "GPT-OSS 120B (OR)",     "openrouterApiKey", "cloud"),
            // ── free-only-Cascade: kostenfreie OpenRouter-Modelle (kein Cooldown) ──
            new Default("openrouter", "deepseek/deepseek-v4-flash:free",          "DeepSeek V4 Flash (free)", "openrouterApiKey", "free-only"),
            new Default("openrouter", "meta-llama/llama-3.3-70b-instruct:free",   "Llama 3.3 70B (free)",  "openrouterApiKey", "free-only"),
            new Default("openrouter", "google/gemma-3-4b-it:free",                "Gemma 3 4B (free)",     "openrouterApiKey", "free-only")
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
        System.out.println("[seed] switcher: Default-Chain geseedet (cloud + free-only Cascades)");
    }

    /**
     * Phase-S'-Migration: Modelle ohne category-Wert bekommen "cloud".
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
            m.setCategory("cloud");
            m.setUpdatedAt(now);
        }
        modelRepo.saveAll(uncategorized);
        System.out.println("[migrate] switcher: " + uncategorized.size()
            + " Modelle ohne category → 'cloud' gesetzt (Phase S'→S'')");
    }

    /**
     * Phase-S''-Migration: Alte Kategorie-Namen umbenennen.
     *
     * • "default"  → "cloud"
     * • "fallback" → "free-only"
     *
     * Idempotent — nach dem ersten Lauf sind alle alten Namen weg,
     * weitere Läufe finden nichts mehr und tun nichts.
     */
    private void migrateOldCategoryNames(AiModelConfigRepository modelRepo) {
        List<AiModelConfig> toMigrate = modelRepo.findAll().stream()
            .filter(m -> "default".equals(m.getCategory()) || "fallback".equals(m.getCategory()))
            .toList();
        if (toMigrate.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        int defaultCount = 0;
        int fallbackCount = 0;
        for (AiModelConfig m : toMigrate) {
            if ("default".equals(m.getCategory())) {
                m.setCategory("cloud");
                defaultCount++;
            } else if ("fallback".equals(m.getCategory())) {
                m.setCategory("free-only");
                fallbackCount++;
            }
            m.setUpdatedAt(now);
        }
        modelRepo.saveAll(toMigrate);
        System.out.println("[migrate] switcher: " + defaultCount + "× default→cloud, "
            + fallbackCount + "× fallback→free-only (Phase S'')");
    }
}
