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

    public String url() { return cascadeUrl; }
}
