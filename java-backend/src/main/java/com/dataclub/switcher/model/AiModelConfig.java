package com.dataclub.switcher.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Konfig pro Modell der LLM-Cascade — Switcher-eigener Context (Phase K).
 *
 * Eine Zeile = ein Modell, das die Cascade in der durch {@code orderIdx} bestimmten
 * Reihenfolge probiert. Der Provider-Typ wird als String abgelegt (kein Enum), damit
 * neue Provider ohne Schema-Migration hinzukommen koennen.
 *
 * Aktive Modelle = {@code enabled && !autoDisabled}. Bei dauerhaftem Fehler vom Provider
 * (z.B. 404 "model deprecated") setzt der Cascade-Service {@code autoDisabled=true} +
 * {@code autoDisabledReason} -- Admin sieht im UI sofort warum und kann entscheiden ob
 * der Eintrag manuell re-enabled oder geloescht wird.
 *
 * Schema-kompatibel mit der gleichnamigen Tabelle von llm-cascade (selbe DB), damit
 * Switcher und Sidecar dieselbe ai_model_config-Tabelle lesen/schreiben koennen.
 * EduPro hat dieselbe Entity (Spiegel) in seinem Backend — Vertrag wird ueber alle
 * Konsumenten konstant gehalten.
 */
@Entity
@Table(name = "ai_model_config",
    indexes = {
        @Index(name = "ix_ai_model_order", columnList = "order_idx"),
        @Index(name = "ix_ai_model_enabled", columnList = "enabled")
    })
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AiModelConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 32, nullable = false)
    private String provider;

    @Column(name = "model_id", length = 128, nullable = false)
    private String modelId;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "api_key_setting_key", length = 100, nullable = false)
    private String apiKeySettingKey;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "order_idx", nullable = false)
    private Integer orderIdx;

    @Column(name = "cooldown_503_override_sec")
    private Integer cooldown503OverrideSec;

    @Column(name = "auto_disabled", nullable = false)
    private Boolean autoDisabled;

    @Column(name = "auto_disabled_reason", length = 500)
    private String autoDisabledReason;

    @Column(name = "auto_disabled_at")
    private LocalDateTime autoDisabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = Boolean.TRUE;
        if (autoDisabled == null) autoDisabled = Boolean.FALSE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
