package com.aifieldservice.repairassistant.integration.qdrant;

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

/**
 * Qdrant REST API 适配器。
 *
 * <p>Qdrant 只保存用于召回的向量和少量过滤 metadata，完整案例仍以 MySQL 为准。
 * 搜索命中后，上层使用 receptionId 回查 MySQL，因此向量库不是业务事实源。
 */
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
        // 轻量读取 collection 状态；失败时让调用方回退到结构化检索。
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
            // 使用命名向量，给未来增加 resolution_vector 等不同检索视角预留空间。
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

        // point id 由业务键稳定生成，重复导入会覆盖同一向量而不是制造重复点。
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
        // 语义相似不能突破业务适用范围；型号和问题类型作为 must filter 硬约束。
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
            // Qdrant 不可用时返回空集合，SQL 精确检索仍可独立完成诊断。
            log.warn("Qdrant query failed: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 只召回已经发布的服务手册知识。
     *
     * <p>手册和维修案例共用同一个命名向量，但通过 knowledgeSource 硬过滤隔离。
     * 返回 MySQL 投影 id 后仍需回查关系库，Qdrant 不承担证据正文的事实源角色。
     */
    public List<ManualSearchHit> searchManual(
            float[] vector,
            String model,
            String problemTypeCode,
            String errorCode,
            int limit,
            double scoreThreshold) {
        if (vector == null || vector.length == 0 || !available()) {
            return List.of();
        }

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of(
                "key", "knowledgeSource",
                "match", Map.of("value", "SERVICE_MANUAL")));
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
        if (errorCode != null && !errorCode.isBlank()) {
            filters.add(Map.of(
                    "key", "errorCode",
                    "match", Map.of("value", errorCode)));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("using", "problem_vector");
        body.put("limit", limit);
        body.put("score_threshold", scoreThreshold);
        body.put("with_payload", true);
        body.put("filter", Map.of("must", filters));
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
            List<ManualSearchHit> hits = new ArrayList<>();
            for (JsonNode point : response.path("result").path("points")) {
                long manualKnowledgeId = point.path("payload")
                        .path("manualKnowledgeId")
                        .asLong(0);
                if (manualKnowledgeId > 0) {
                    hits.add(new ManualSearchHit(
                            point.path("id").asText(),
                            point.path("score").asDouble(),
                            manualKnowledgeId));
                }
            }
            return hits;
        } catch (RestClientException exception) {
            log.warn("Qdrant manual query failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public record VectorPoint(String id, float[] vector, Map<String, Object> payload) {
    }

    public record SearchHit(String pointId, double score, String receptionId) {
    }

    public record ManualSearchHit(String pointId, double score, long manualKnowledgeId) {
    }
}
