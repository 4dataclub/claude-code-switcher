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

    public String url() { return cascadeUrl; }
}
