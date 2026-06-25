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
     * Preferred-Category lesen (v0.7.5 — Library-Komponente "Modus-Panel"
     * im Switcher zeigt die aktive Kategorie als Toggle).
     */
    public JsonNode getPreferredCategory() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/preferred-category", String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            com.fasterxml.jackson.databind.node.ObjectNode n = mapper.createObjectNode();
            n.put("category", "");
            n.put("active", false);
            n.put("note", "Cascade < 0.7.5 oder nicht erreichbar");
            return n;
        }
    }

    /**
     * Preferred-Category setzen — Body {@code {"category": "cloud"}} oder
     * {@code {"category": ""}} (Empty = zurueck zu Semantic Routing).
     */
    public boolean setPreferredCategory(String category) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(Map.of("category", category == null ? "" : category), headers);
            rest.postForObject(cascadeUrl + "/api/preferred-category", req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Performance-Stats pro (provider, model) der letzten 30 Tage
     * (Library v0.14.0 / Cascade ≥ 0.7.6).
     */
    public JsonNode getPerformance(String sortBy) {
        try {
            String url = cascadeUrl + "/api/stats/performance"
                + (sortBy == null || sortBy.isBlank() ? "" : "?sortBy=" + sortBy);
            String json = rest.getForObject(url, String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * Letzte Delegations-Calls (Tail aus dem call_log) — Proxy zu
     * GET /api/stats/calls. Wird vom <ki-delegation-live> im Auto-Refresh
     * aufgerufen. Bei Cascade unreachable: leeres Array (graceful fallback).
     */
    public JsonNode getDelegationCalls() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/stats/calls", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * v0.18.0 — Erfolgs-Trend (Calls/Tag, success/failed) — Proxy zu
     * GET /api/stats/trend. Speist {@code <ki-call-overview>}. Leeres
     * Array bei Cascade unreachable.
     */
    public JsonNode getStatsTrend(int days) {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/stats/trend?days=" + days, String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /**
     * v0.18.0 — KI-Calls-Totals (24h/7d/30d + Erfolg/Fehlschlag + Chars) —
     * Proxy zu GET /api/stats/totals. Leeres Objekt bei Cascade unreachable.
     */
    public JsonNode getStatsTotals() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/stats/totals", String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /**
     * v0.18.0 — Failover-Aufschluesselung (Provider/Grund) — Proxy zu
     * GET /api/stats/failover-breakdown. Speist {@code <ki-failover-analytics>}.
     * Leeres Objekt bei Cascade unreachable.
     */
    public JsonNode getStatsFailoverBreakdown() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/stats/failover-breakdown", String.class);
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /**
     * v0.19.0 — Failover-Events-Timeline (letzte 50 + total30d) — Proxy zu
     * GET /api/stats/failover. Speist die Timeline in {@code <ki-failover-analytics>}.
     * Leeres Objekt ({@code {recent:[],total30d:0}}) bei Cascade unreachable.
     */
    public JsonNode getStatsFailover() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/stats/failover", String.class);
            if (json == null) {
                var fallback = mapper.createObjectNode();
                fallback.set("recent", mapper.createArrayNode());
                fallback.put("total30d", 0);
                return fallback;
            }
            return mapper.readTree(json);
        } catch (Exception e) {
            var fallback = mapper.createObjectNode();
            fallback.set("recent", mapper.createArrayNode());
            fallback.put("total30d", 0);
            return fallback;
        }
    }

    /**
     * v0.19.0 — Loggt eine Host-seitige Umschaltung (Pool-Wechsel / Supermodell
     * an-aus) in die Cascade-Events-Timeline. Fire-and-forget: Fehler werden
     * geschluckt, ein nicht erreichbares Cascade-Backend darf den Mode-Switch
     * NIE blockieren.
     */
    public void logEvent(String type, String fromModel, String toModel, String reason) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode req = mapper.createObjectNode();
            req.put("type", type);
            if (fromModel != null) req.put("fromModel", fromModel);
            if (toModel != null) req.put("toModel", toModel);
            if (reason != null) req.put("reason", reason);
            rest.postForObject(cascadeUrl + "/api/events/log", req, String.class);
        } catch (Exception ignored) {
            // Logging ist best-effort — nie den Mode-Switch hängen lassen.
        }
    }

    /**
     * Cooldown + Auto-Disable State pro Modell. Wird mit Auto-Refresh
     * vom <ki-models-cooldown-state> aufgerufen.
     */
    public JsonNode getCooldownStateList() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/cooldown-state", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
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

    // ─── Provider-Server-Proxy (v0.8.0 — externe Inferenz-Server pro Modell) ──
    // CRUD liegt in llm-cascade (/api/provider-servers). Hier nur durchgereicht,
    // damit <ki-provider-servers> (KI_MODELS_API_BASE='/api') funktioniert.

    /** Liste der Inferenz-Server — Proxy zu GET /api/provider-servers. */
    public JsonNode getProviderServers() {
        try {
            String json = rest.getForObject(cascadeUrl + "/api/provider-servers", String.class);
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    /** Upsert eines Servers — Proxy zu PUT /api/provider-servers/{name}. Body: {baseUrl, isDefault?, description?}. */
    public boolean upsertProviderServer(String name, JsonNode body) {
        try {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<String> req = new org.springframework.http.HttpEntity<>(
                body == null ? "{}" : mapper.writeValueAsString(body), h);
            rest.exchange(cascadeUrl + "/api/provider-servers/" + name,
                org.springframework.http.HttpMethod.PUT, req, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Löscht einen Server — Proxy zu DELETE /api/provider-servers/{name} (Default nicht löschbar). */
    public boolean deleteProviderServer(String name) {
        try {
            rest.delete(cascadeUrl + "/api/provider-servers/" + name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String url() { return cascadeUrl; }
}
