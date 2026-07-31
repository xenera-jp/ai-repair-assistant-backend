package com.aifieldservice.repairassistant.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGateway.class);

    private final RepairAssistantProperties properties;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public OpenAiGateway(
            RepairAssistantProperties properties,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.openai().baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.openai().apiKey())
                .build();
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.openai().apiKey() != null
                && !properties.openai().apiKey().isBlank();
    }

    public List<float[]> embed(List<String> inputs) {
        if (!enabled() || inputs.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", properties.openai().embeddingModel(),
                    "dimensions", properties.openai().embeddingDimensions(),
                    "encoding_format", "float",
                    "input", inputs);
            JsonNode response = client.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("data").isArray()) {
                return List.of();
            }

            List<float[]> embeddings = new ArrayList<>(
                    Collections.nCopies(inputs.size(), null));
            for (JsonNode item : response.path("data")) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= embeddings.size()) {
                    continue;
                }
                JsonNode vector = item.path("embedding");
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                embeddings.set(index, values);
            }
            return embeddings.stream().allMatch(value -> value != null)
                    ? embeddings
                    : List.of();
        } catch (RestClientException exception) {
            log.warn("OpenAI embedding request failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public Optional<String> explainDiagnosis(
            String question,
            String problemType,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> evidence) {
        if (!enabled()) {
            return Optional.empty();
        }

        String prompt = """
                你是企业设备维保诊断助手。仅根据给定的结构化候选和证据，生成一段不超过120字的中文诊断摘要。
                不得创造部件号、案例编号、测量值或官方结论；证据不足时必须明确写出需要现场确认。

                用户问题：
                %s

                问题分类：
                %s

                候选原因：
                %s

                检索证据：
                %s
                """.formatted(
                        question,
                        problemType,
                        writeJson(candidates),
                        writeJson(evidence));

        try {
            Map<String, Object> body = Map.of(
                    "model", properties.openai().chatModel(),
                    "store", false,
                    "input", prompt);
            JsonNode response = client.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            for (JsonNode output : response.path("output")) {
                for (JsonNode content : output.path("content")) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) {
                        return Optional.of(text.trim());
                    }
                }
            }
        } catch (RestClientException exception) {
            log.warn("OpenAI diagnosis explanation failed: {}", exception.getMessage());
        }
        return Optional.empty();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
