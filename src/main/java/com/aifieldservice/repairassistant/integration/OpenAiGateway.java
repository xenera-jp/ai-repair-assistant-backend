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

/**
 * OpenAI API 的防腐层（Anti-corruption Layer）。
 *
 * <p>业务服务只调用“生成向量”和“基于证据解释诊断”两个能力，不直接依赖
 * OpenAI 的 HTTP JSON 格式。外部调用失败时返回空结果，由上层继续使用确定性结果，
 * 因而 OpenAI 暂时不可用不会破坏 SQL 诊断主链路。
 */
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
        // 未配置密钥时视为可预期的本地降级模式，不在这里抛异常。
        return properties.openai().apiKey() != null
                && !properties.openai().apiKey().isBlank();
    }

    public List<float[]> embed(List<String> inputs) {
        if (!enabled() || inputs.isEmpty()) {
            return List.of();
        }

        try {
            // dimensions 与 Qdrant collection 建表维度必须一致，否则 upsert 会被拒绝。
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

            // OpenAI 响应通过 index 指回输入位置；按 index 重排可保持批量导入顺序稳定。
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
            // 批次中缺少任意一个向量时整体失败，避免把向量错配到其他维修案例。
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
            String language,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> evidence) {
        if (!enabled()) {
            return Optional.empty();
        }

        // LLM 只负责把已有候选和证据组织成易读解释，不负责召回、创造候选或给分。
        // 这是限制幻觉和保持可追溯性的关键边界。
        boolean japanese = "ja-JP".equals(language);
        String prompt = (japanese
                ? """
                        あなたは企業設備の保守診断アシスタントです。与えられた構造化候補と証拠だけを使用し、
                        120文字以内の簡潔な日本語診断要約を作成してください。部品番号、事例番号、測定値、
                        公式見解を新たに作ってはいけません。証拠が不足する場合は、現場確認が必要だと明記してください。

                        ユーザーの質問：
                        %s

                        問題分類：
                        %s

                        原因候補：
                        %s

                        検索証拠：
                        %s
                        """
                : """
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
                        """).formatted(
                                question,
                                problemType,
                                writeJson(candidates),
                                writeJson(evidence));

        try {
            // store=false 避免把企业维修上下文保存为 OpenAI API 的持久响应对象。
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
            // 解释生成失败时，上层保留规则生成的候选解释，不中断诊断。
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
