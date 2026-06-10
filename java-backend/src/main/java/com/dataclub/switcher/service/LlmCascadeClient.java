package com.dataclub.switcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** Thin HTTP-Client zur llm-cascade-Sidecar (Modell-Liste + Health). */
@Service
public class LlmCascadeClient {

    @Value("${llm.cascade.url}")
    private String cascadeUrl;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode getModels() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/models", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (RestClientException | java.io.IOException e) {
            return mapper.createArrayNode();
        }
    }

    public boolean isHealthy() {
        try {
            rest.getForObject(cascadeUrl + "/api/health/keys", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Settings-Liste (mit maskierten Werten). Proxy zu GET /api/settings. */
    public JsonNode getSettings() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/settings", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /** Setting-Wert setzen. Proxy zu POST /api/settings/{key} mit {value}. */
    public boolean setSetting(String key, String value) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(Map.of("value", value == null ? "" : value), headers);
            rest.postForObject(cascadeUrl + "/api/settings/" + key, req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Neues Modell anlegen — POST /api/models. */
    public JsonNode createModel(Map<String, Object> body) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(body, headers);
            String json = rest.postForObject(cascadeUrl + "/api/models", req, String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode().put("ok", false).put("error", e.getMessage());
        }
    }

    /** Modell loeschen — DELETE /api/models/{id}. */
    public boolean deleteModel(long id) {
        try {
            rest.delete(cascadeUrl + "/api/models/" + id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Modell testen — POST /api/models/{id}/test. */
    public JsonNode testModel(long id) {
        try {
            String json = rest.postForObject(cascadeUrl + "/api/models/" + id + "/test", null, String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode().put("ok", false).put("error", e.getMessage());
        }
    }

    /** Reorder — POST /api/models/reorder mit {orderedIds: [...]}. */
    public boolean reorderModels(java.util.List<Long> orderedIds) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(Map.of("orderedIds", orderedIds), headers);
            rest.postForObject(cascadeUrl + "/api/models/reorder", req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Modell-Patch (enabled-Toggle etc.) — proxy zu llm-cascade PUT /api/models/{id}. */
    public boolean patchModel(long id, Map<String, Object> patch) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(patch, headers);
            rest.exchange(cascadeUrl + "/api/models/" + id, org.springframework.http.HttpMethod.PUT, req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cascade-Bereiche — Proxy zu GET /api/cascades.
     * Liefert pro distinct category: name, currentModel, cooldownSec.
     * Wird vom Switcher-Frontend via {@code <ki-cascades-view>} genutzt.
     */
    public JsonNode getCascades() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/cascades", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * Display-Metadaten pro Kategorie — Proxy zu GET /api/categories.
     * Liefert pro Kategorie: name, displayName, description, orderIdx.
     * Wird vom Frontend-Library-Component {@code <ki-cascades-view>} (für
     * Title/Hint pro Bereich) + {@code <ki-add-model-form>} (für das
     * Kategorie-Dropdown beim Anlegen) gelesen. Fallback: leeres Array.
     */
    public JsonNode getCategories() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/categories", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * Upsert für die Metadaten einer Kategorie — Proxy zu PUT /api/categories/{name}.
     * Body wird unverändert durchgereicht: {displayName?, description?, orderIdx?}.
     * Liefert true bei HTTP 2xx, false sonst.
     */
    public boolean updateCategory(String name, JsonNode body) {
        try {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<String> req = new org.springframework.http.HttpEntity<>(
                body == null ? "{}" : mapper.writeValueAsString(body), h);
            rest.exchange(cascadeUrl + "/api/categories/" + name,
                org.springframework.http.HttpMethod.PUT, req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Löscht NUR die Metadaten-Zeile (Kategorie selbst bleibt). */
    public boolean deleteCategoryMeta(String name) {
        try {
            rest.delete(cascadeUrl + "/api/categories/" + name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Quality-Stats pro Modell — Proxy zu GET /api/stats/quality (llm-cascade
     * ≥ 0.7.2). Liefert pro Modell {@code QualityStatRow} mit Score, Tier
     * (★/◐/▽/✗/?), successRate, avgChars, callsLast30d.
     *
     * Default-Sortierung „worst-first" damit KILL-Kandidaten in der
     * UI oben sichtbar sind. Wird von der Library-Component
     * {@code <ki-models-quality-stats>} aus {@code @4dataclub/ki-models-ui}
     * v0.12.0 aufgerufen.
     *
     * Bei Backend-Fehler/Verfügbarkeitsproblem (z.B. llm-cascade unreachable
     * oder Version &lt; 0.7.2) → leeres Array. Frontend zeigt dann „keine
     * Daten" statt zu crashen.
     */
    public JsonNode getQualityStats(String sortBy) {
        try {
            String url = cascadeUrl + "/api/stats/quality"
                + (sortBy == null || sortBy.isBlank() ? "" : "?sortBy=" + sortBy);
            String json = rest.getForObject(url, String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * Manueller Trigger für den llm-cascade Quality-Auto-Disable-Job
     * (Library v0.12.1 / Cascade ≥ 0.7.3). Proxy zu POST
     * /api/quality/run-auto-disable.
     *
     * Bei Cascade unreachable/alt: leeres Report-Objekt mit error-Hinweis
     * — Library-Component zeigt graceful „nicht verfügbar"-Banner.
     */
    public JsonNode runQualityAutoDisable() {
        try {
            String json = rest.postForObject(
                cascadeUrl + "/api/quality/run-auto-disable", null, String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            com.fasterxml.jackson.databind.node.ObjectNode n = mapper.createObjectNode();
            n.put("checked", 0);
            n.set("disabled", mapper.createArrayNode());
            n.put("error", "Cascade nicht erreichbar oder Version < 0.7.3");
            return n;
        }
    }

    /**
     * Config-Endpoint für Auto-Disable: {@code {enabled, minCalls, note}}.
     * Wird vom Library-Component beim Mount geholt um zu entscheiden ob
     * der „Auto-Disable jetzt"-Button gerendert wird.
     */
    public JsonNode getQualityAutoDisableConfig() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/quality/auto-disable-config", String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            com.fasterxml.jackson.databind.node.ObjectNode n = mapper.createObjectNode();
            n.put("enabled", false);
            n.put("minCalls", 50);
            n.put("note", "Cascade < 0.7.3");
            return n;
        }
    }

    public String url() { return cascadeUrl; }
}
