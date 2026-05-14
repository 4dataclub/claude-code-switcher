package com.dataclub.switcher.service;

import com.dataclub.switcher.model.AiModelConfig;
import com.dataclub.switcher.model.AppSetting;
import com.dataclub.switcher.repository.AiModelConfigRepository;
import com.dataclub.switcher.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Switcher-eigener Modell-Context (Phase K, 2026-05-14).
 *
 * Vorher: Switcher-Admin proxied alle CRUD-Operationen via HTTP an llm-cascade.
 * Jetzt: Switcher pflegt seine Modell-Liste + Settings lokal in der eigenen
 * Postgres-DB. llm-cascade bleibt als Engine — Provider-Calls (Generate, Test)
 * gehen weiter dort durch.
 *
 * **Sensitive Settings:** Werte werden im Repo plain gespeichert. Beim Lesen
 * via {@link #listSettings()} werden API-Keys maskiert (analog zur
 * llm-cascade-API). {@link #getSettingRaw(String)} liefert Klartext fuer
 * interne Provider-Calls.
 */
@Service
public class SwitcherModelService {

    @Autowired private AiModelConfigRepository modelRepo;
    @Autowired private AppSettingRepository settingRepo;

    // Pattern: settingKey enthaelt "key" oder "token" -> maskieren beim Listen.
    private static final java.util.regex.Pattern SENSITIVE_KEY =
        java.util.regex.Pattern.compile("(?i)(key|token|secret|password)");

    // ─── Models ──────────────────────────────────────────────────────────────

    public List<AiModelConfig> listModels() {
        return modelRepo.findAllByOrderByOrderIdxAsc();
    }

    @Transactional
    public AiModelConfig createModel(Map<String, Object> body) {
        AiModelConfig m = new AiModelConfig();
        m.setProvider((String) body.get("provider"));
        m.setModelId((String) body.get("modelId"));
        m.setDisplayName((String) body.getOrDefault("displayName", null));
        m.setApiKeySettingKey((String) body.getOrDefault(
            "apiKeySettingKey", m.getProvider() + "ApiKey"));
        m.setEnabled(body.get("enabled") instanceof Boolean b ? b : Boolean.TRUE);
        m.setAutoDisabled(Boolean.FALSE);
        // orderIdx an Ende der Liste, sofern nicht explizit gesetzt.
        Integer orderIdx = body.get("orderIdx") instanceof Number n ? n.intValue() : null;
        if (orderIdx == null) {
            int max = modelRepo.findAll().stream()
                .mapToInt(AiModelConfig::getOrderIdx).max().orElse(-1);
            orderIdx = max + 1;
        }
        m.setOrderIdx(orderIdx);
        if (body.get("cooldown503OverrideSec") instanceof Number cn) {
            m.setCooldown503OverrideSec(cn.intValue());
        }
        return modelRepo.save(m);
    }

    @Transactional
    public Optional<AiModelConfig> patchModel(long id, Map<String, Object> patch) {
        return modelRepo.findById(id).map(m -> {
            if (patch.containsKey("enabled") && patch.get("enabled") instanceof Boolean b) {
                m.setEnabled(b);
            }
            if (patch.containsKey("autoDisabled") && patch.get("autoDisabled") instanceof Boolean b) {
                m.setAutoDisabled(b);
                if (!b) {
                    m.setAutoDisabledReason(null);
                    m.setAutoDisabledAt(null);
                }
            }
            if (patch.containsKey("cooldown503OverrideSec")) {
                Object v = patch.get("cooldown503OverrideSec");
                m.setCooldown503OverrideSec(v instanceof Number cn ? cn.intValue() : null);
            }
            if (patch.containsKey("displayName")) {
                m.setDisplayName((String) patch.get("displayName"));
            }
            return modelRepo.save(m);
        });
    }

    @Transactional
    public boolean deleteModel(long id) {
        if (!modelRepo.existsById(id)) return false;
        modelRepo.deleteById(id);
        return true;
    }

    @Transactional
    public boolean reorderModels(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return false;
        // Map<id, model> einmal lesen, dann durchnummerieren.
        Map<Long, AiModelConfig> byId = new HashMap<>();
        for (AiModelConfig m : modelRepo.findAllById(orderedIds)) byId.put(m.getId(), m);
        if (byId.size() != orderedIds.size()) return false;   // unbekannte IDs
        int idx = 0;
        for (Long id : orderedIds) {
            AiModelConfig m = byId.get(id);
            m.setOrderIdx(idx++);
            modelRepo.save(m);
        }
        return true;
    }

    public boolean modelHasKey(AiModelConfig m) {
        String v = getSettingRaw(m.getApiKeySettingKey());
        return v != null && !v.isBlank();
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    /** Liste aller Settings; sensible Werte werden maskiert. */
    public List<Map<String, Object>> listSettings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppSetting s : settingRepo.findAll()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", s.getKey());
            String v = s.getValue();
            boolean configured = v != null && !v.isBlank();
            boolean sensitive = SENSITIVE_KEY.matcher(s.getKey()).find();
            entry.put("valueMasked", configured && sensitive ? mask(v) : (configured ? v : ""));
            entry.put("configured", configured);
            out.add(entry);
        }
        return out;
    }

    /** Klartext-Setting (fuer interne Calls — z.B. Provider-Test). */
    public String getSettingRaw(String key) {
        return settingRepo.findById(key).map(AppSetting::getValue).orElse(null);
    }

    /** Setting setzen; leerer Wert loescht den Eintrag. */
    @Transactional
    public boolean setSetting(String key, String value) {
        if (value == null || value.isBlank()) {
            if (settingRepo.existsById(key)) settingRepo.deleteById(key);
            return true;
        }
        AppSetting s = settingRepo.findById(key).orElseGet(() -> {
            AppSetting nu = new AppSetting();
            nu.setKey(key);
            return nu;
        });
        s.setValue(value);
        settingRepo.save(s);
        return true;
    }

    private static String mask(String v) {
        if (v == null || v.length() < 8) return "***";
        return v.substring(0, 4) + "••••••••" + v.substring(v.length() - 4);
    }
}
