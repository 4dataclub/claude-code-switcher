package com.dataclub.switcher.repository;

import com.dataclub.switcher.model.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {

    /** Reihenfolge in der die Cascade Modelle probiert -- ueberspringt deaktivierte + auto-disabled. */
    List<AiModelConfig> findByEnabledTrueAndAutoDisabledFalseOrderByOrderIdxAsc();

    /** Alle (auch deaktivierte/auto-disabled), fuer Admin-UI sortiert. */
    List<AiModelConfig> findAllByOrderByOrderIdxAsc();

    /** Dedup-Check beim Seed: gleicher Provider + Modell-ID = bereits vorhanden. */
    Optional<AiModelConfig> findFirstByProviderAndModelId(String provider, String modelId);

    /** Kategorie-bewusster Dedup-Check beim Matrix-Seed: dasselbe Modell darf in
     *  mehreren Compound-Kategorien stehen (z.B. {@code qwen2.5:7b} als review-local
     *  UND research-local) — Dedup nur auf der vollen Zelle (Provider+Modell+Kategorie). */
    Optional<AiModelConfig> findFirstByProviderAndModelIdAndCategory(String provider, String modelId, String category);
}
