package com.dataclub.switcher.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Switcher-Settings als Key/Value-Paare — Phase K (2026-05-14).
 *
 * Ersetzt die UI-relevanten Teile der `~/.claude/settings.json` (z.B. API-Keys,
 * Cooldown-Override) durch DB-Storage. CLI-Operativ-Daten (activeRoute,
 * chain_position) bleiben in der JSON-Datei.
 *
 * Schema-kompatibel mit llm-cascade's app_settings + EduPro's app_settings.
 */
@Entity
@Table(name = "app_settings")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", length = 1000)
    private String value;
}
