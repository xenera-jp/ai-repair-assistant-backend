package com.aifieldservice.repairassistant.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import tools.jackson.databind.JsonNode;

@Component
public class QdrantGateway {

    private static final Logger log = LoggerFactory.getLogger(QdrantGateway.class);

    private final RepairAssistantProperties properties;
    private final RestClient client;

    public QdrantGateway(
            RepairAssistantProperties properties,
            RestClient.Builder builder) {
        this.properties = properties;
        RestClient.Builder configured = builder.baseUrl(properties.qdrant().url());
        if (properties.qdrant().apiKey() != null
                && !properties.qdrant().apiKey().isBlank()) {
            configured.defaultHeader("api-key", properties.qdrant().apiKey());
        }
        this.client = configured.build();
    }

    public boolean available() {
        try {
            JsonNode response = client.get()
                    .uri("/collections/{collection}", properties.qdrant().collection())
                    .retrieve()
                    .body(JsonNode.class);
            return response != null
                    && "ok".equalsIgnoreCase(response.path("status").asText());
        } catch (RestClientException exception) {
            return false;
        }
    }

    public boolean ensureCollection() {
        if (available()) {
            return true;
        }
        try {
            Map<String, Object> vectors = Map.of(
                    "problem_vector",
                    Map.of(
                            "size", properties.openai().embeddingDimensions(),
                            "distance", "Cosine"));
            client.put()
                    .uri("/collections/{collection}", properties.qdrant().collection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("vectors", vectors))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            log.warn("Qdrant collection setup failed: {}", exception.getMessage());
            return false;
        }
    }

    public boolean upsert(List<VectorPoint> points) {
        if (points.isEmpty() || !ensureCollection()) {
            return false;
        }

        List<Map<String, Object>> payloadPoints = points.stream()
                .map(point -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", point.id());
                    value.put("vector", Map.of("problem_vector", point.vector()));
                    value.put("payload", point.payload());
                    return value;
                })
                .toList();
        try {
            client.put()
                    .uri("/collections/{collection}/points?wait=true",
                            properties.qdrant().collection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", payloadPoints))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            log.warn("Qdrant upsert failed: {}", exception.getMessage());
            return false;
        }
    }

    public List<SearchHit> search(
            float[] vector,
            String model,
            String problemTypeCode,
            int limit,
            double scoreThreshold) {
        if (vector == null || vector.length == 0 || !available()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("using", "problem_vector");
        body.put("limit", limit);
        body.put("score_threshold", scoreThreshold);
        body.put("with_payload", true);
        List<Map<String, Object>> filters = new ArrayList<>();
        if (model != null && !model.isBlank()) {
            filters.add(Map.of(
                    "key", "model",
                    "match", Map.of("value", model)));
        }
        if (problemTypeCode != null && !problemTypeCode.isBlank()) {
            filters.add(Map.of(
                    "key", "problemTypeCode",
                    "match", Map.of("value", problemTypeCode)));
        }
        if (!filters.isEmpty()) {
            body.put("filter", Map.of(
                    "must", filters));
        }

        try {
            JsonNode response = client.post()
                    .uri("/collections/{collection}/points/query",
                            properties.qdrant().collection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return List.of();
            }
            JsonNode points = response.path("result").path("points");
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode point : points) {
                hits.add(new SearchHit(
                        point.path("id").asText(),
                        point.path("score").asDouble(),
                        point.path("payload").path("receptionId").asText()));
            }
            return hits;
        } catch (RestClientException exception) {
            log.warn("Qdrant query failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public record VectorPoint(String id, float[] vector, Map<String, Object> payload) {
    }

    public record SearchHit(String pointId, double score, String receptionId) {
    }
}
