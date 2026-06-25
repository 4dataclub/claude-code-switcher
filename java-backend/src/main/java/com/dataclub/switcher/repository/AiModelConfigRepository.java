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

    /** Kategorie-bewusster Dedup-Check beim Seed: dasselbe Modell darf in mehreren
     *  Cascade-Kategorien stehen (z.B. deepseek in implement-cloud UND im Pool free),
     *  nur dieselbe (Provider, Modell-ID, Kategorie) zaehlt als Duplikat. */
    Optional<AiModelConfig> findFirstByProviderAndModelIdAndCategory(String provider, String modelId, String category);
}
