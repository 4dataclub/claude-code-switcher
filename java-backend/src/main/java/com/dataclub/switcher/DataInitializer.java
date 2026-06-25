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
     * Default-Seed für frische DBs — die **2D-Supermodell-Matrix** (Rolle × Pool).
     *
     * Compound-Kategorien {@code {implement|review|research|dispatch}-{cloud|free|local}}
     * sind die Routing-Ziele des Supermodell-Modus (Opus delegiert via
     * {@code @supermodel} an {@code category={kind}-{pool}}). Ohne diesen Seed wären
     * sie nur Laufzeit-DB-State und bei {@code docker compose down -v} weg
     * (Setup-Kanonik / feedback_fix_drives_setup_update).
     *
     * <pre>
     *            cloud (bezahlt)        free (:free, €0)       local (Ollama, privat)
     * implement  DeepSeek V3.1 + Flash  Qwen3-Coder + Next-80B qwen2.5-coder:7b *
     * review     Haiku 4.5 + GPT-4o-mini GPT-OSS 120B          qwen2.5:7b *
     * research   Gemini Pro (OR+nativ)  Gemma 4 31B (free)     qwen2.5:7b * (intern/Intranet, offline, nichts raus)
     * dispatch   Gemini Flash-Lite      Llama 3.3 + GPT-OSS-20 gemma3:4b *
     * </pre>
     * (* local = enabled=false bis die Ollama-Modelle gezogen sind — Phase E.)
     *
     * {@code orchestrator-cloud} trägt Opus (Abo) als Orchestrator-Primary (Failover → Gemini Flash).
     * {@code utility}+{@code general} bewusst NICHT geseedet → Local hat
     * keinen Cloud-{@code general}-Fallback = automatisch fail-closed.
     *
     * <p><b>Offline-Garantie:</b> jede {@code *-local}-Zelle routet ausschließlich auf
     * {@code ollama} — KEIN Cloud-Eintrag in irgendeiner local-Zelle. Damit läuft der
     * Local-Pool ohne Internet (sobald die Modelle gezogen sind). {@code research-local}
     * ist legitim: ein lokales Modell für Doc-Analyse/Reasoning, das lokale Docs +
     * interne/VPN-erreichbare Ressourcen verarbeitet — die fail-closed-Grenze ist das
     * <i>interne Netz</i>, nicht „die Maschine". Öffentliches Web/Cloud verlässt nie das
     * interne Netz; reine Public-Web-Recherche verweigert der Agent (fail-closed).
     */
    private void seedDefaultChain(AiModelConfigRepository modelRepo) {
        LocalDateTime now = LocalDateTime.now();
        record Default(String provider, String modelId, String displayName, String settingKey, String category, boolean enabled) {}
        List<Default> defaults = List.of(
            // ── orchestrator: Claude Code selbst (Opus-Abo bleibt Orchestrator) — Failover-Kette bei Opus-Limit
            //    (datengetrieben: setMode liest orchestrator-{pool} der Reihe nach) ──
            new Default("anthropic",  "claude-opus-4-8",                        "Claude Opus 4.8 (Orchestrator)", "anthropicApiKey", "orchestrator-cloud", true),
            new Default("gemini",     "gemini-2.5-flash",                       "Gemini 2.5 Flash (Orchestrator-Failover #2)", "geminiApiKey",  "orchestrator-cloud", true),
            new Default("ollama",     "qwen2.5:14b",                            "Qwen2.5 14B (lokaler Orchestrator)",        "ollamaApiKey",    "orchestrator-local", false),
            new Default("openrouter", "nousresearch/hermes-3-llama-3.1-405b:free", "Hermes-3 405B (free · Orchestrator)",   "openrouterApiKey", "orchestrator-free",  true),
            // ── implement (Bulk-Code) ──
            new Default("openrouter", "deepseek/deepseek-chat-v3.1",            "DeepSeek V3.1",                  "openrouterApiKey", "implement-cloud", true),
            new Default("openrouter", "google/gemini-2.5-flash",                "Gemini 2.5 Flash",              "openrouterApiKey", "implement-cloud", true),
            new Default("openrouter", "qwen/qwen3-coder:free",                  "Qwen3 Coder (free)",            "openrouterApiKey", "implement-free",  true),
            new Default("openrouter", "qwen/qwen3-next-80b-a3b-instruct:free",  "Qwen3-Next 80B (free)",         "openrouterApiKey", "implement-free",  true),
            new Default("ollama",     "qwen2.5-coder:7b",                       "Qwen2.5 Coder 7B (lokal)",      "ollamaApiKey",     "implement-local", false),
            // ── review (Korrektheit/Tests) — Claude Haiku 4.5 (via OpenRouter, kein Abo-Limit) als primär, GPT-4o-mini als Fallback ──
            new Default("openrouter", "anthropic/claude-haiku-4.5",             "Claude Haiku 4.5 (Anthropic via OpenRouter · Review)", "openrouterApiKey", "review-cloud", true),
            new Default("openrouter", "openai/gpt-4o-mini",                     "GPT-4o-mini",                   "openrouterApiKey", "review-cloud",    true),
            new Default("openrouter", "openai/gpt-oss-120b:free",               "GPT-OSS 120B (free)",           "openrouterApiKey", "review-free",     true),
            new Default("ollama",     "qwen2.5:7b",                             "Qwen2.5 7B (lokal)",            "ollamaApiKey",     "review-local",    false),
            // ── research (Docs · Large-Context · Reasoning) ──
            //    cloud/free: Web-Grounding via Gemini-MCP. local: lokales Modell für
            //    Doc-Analyse/Reasoning (spiegelt review) — verarbeitet lokale Docs +
            //    interne/VPN-erreichbare Ressourcen, nichts verlässt das interne Netz,
            //    kein öffentliches Web/Cloud. enabled=false bis Ollama-Pull (Phase E).
            new Default("openrouter", "google/gemini-2.5-pro",                  "Gemini 2.5 Pro (research)",     "openrouterApiKey", "research-cloud",  true),
            new Default("gemini",     "gemini-2.5-pro",                         "Gemini 2.5 Pro (nativ · #2)",   "geminiApiKey",     "research-cloud",  true),
            new Default("openrouter", "google/gemma-4-31b-it:free",             "Gemma 4 31B (free · Research)",  "openrouterApiKey", "research-free",   true),
            new Default("ollama",     "qwen2.5:7b",                             "Qwen2.5 7B (lokal · Research)", "ollamaApiKey",     "research-local",  false),
            // ── dispatch (Triviales) ──
            new Default("openrouter", "google/gemini-2.5-flash-lite",           "Gemini 2.5 Flash-Lite",         "openrouterApiKey", "dispatch-cloud",  true),
            new Default("openrouter", "meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)",          "openrouterApiKey", "dispatch-free",   true),
            new Default("openrouter", "openai/gpt-oss-20b:free",                "GPT-OSS 20B (free)",            "openrouterApiKey", "dispatch-free",   true),
            new Default("ollama",     "gemma3:4b",                              "Gemma 3 4B (lokal)",            "ollamaApiKey",     "dispatch-local",  false)
        );
        int idx = 0;
        for (Default d : defaults) {
            // Dedup falls llm-cascade dieselbe Tabelle bereits befuellt hat — kategorie-bewusst,
            // damit dasselbe Modell in mehreren Zellen stehen darf (z.B. qwen2.5:7b als
            // review-local UND research-local).
            if (modelRepo.findFirstByProviderAndModelIdAndCategory(d.provider(), d.modelId(), d.category()).isPresent()) {
                continue;
            }
            modelRepo.save(AiModelConfig.builder()
                .provider(d.provider())
                .modelId(d.modelId())
                .displayName(d.displayName())
                .apiKeySettingKey(d.settingKey())
                .category(d.category())
                .enabled(d.enabled())
                .orderIdx(idx++)
                .autoDisabled(Boolean.FALSE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }
        System.out.println("[seed] switcher: 2D-Supermodell-Matrix geseedet (Rolle×Pool, local disabled bis Ollama)");
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
