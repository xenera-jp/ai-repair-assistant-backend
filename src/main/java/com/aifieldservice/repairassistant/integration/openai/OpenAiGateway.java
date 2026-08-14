package com.aifieldservice.repairassistant.integration.openai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        // 无 Key 是正式支持的规则降级模式；此时连 HTTP 客户端也不创建，
        // 避免本地/离线部署在应用启动阶段发生网络初始化。
        this.client = hasApiKey(properties)
                ? builder.baseUrl(properties.openai().baseUrl())
                        .defaultHeader("Authorization", "Bearer " + properties.openai().apiKey())
                        .build()
                : null;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        // 未配置密钥时视为可预期的本地降级模式，不在这里抛异常。
        return hasApiKey(properties);
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

    /**
     * 受控地解析报障文本。调用方只会提供已审核 taxonomy，且仍需在领域层验证结果。
     *
     * <p>网关只处理 OpenAI Responses 的协议与异常；不在这里决定分类是否可采纳，
     * 以免外部供应商格式渗入领域规则。
     */
    public Optional<SemanticProblemUnderstandingResponse> understandProblem(
            String originalText,
            String language,
            List<Map<String, Object>> taxonomy,
            List<String> allowedFields) {
        if (!enabled()) {
            return Optional.empty();
        }

        boolean japanese = "ja-JP".equals(language);
        String prompt = (japanese
                ? """
                        あなたは設備保守の入力解析器です。問題の症状を文全体の意味、否定、時制、前回との比較から理解してください。
                        problemTypeCode は TAXONOMY の code からのみ選びます。症状に最も意味的に近い既存カテゴリを積極的に選び、合理的な候補がまったくない場合だけ UNCLASSIFIED を返してください。
                        TAXONOMY の aliases、modelScopes、errorCodes は意味理解の補助情報です。型式・エラーコードの未記載や語句の完全一致を理由にカテゴリ選択を見送らないでください。
                        fields は ALLOWED_FIELDS 内だけにし、status は PRESENT または ABSENT にしてください。
                        明示的に「表示なし」「問題なし」と述べた情報は ABSENT または PRESENT の正常状態として抽出してください。
                        原文から値を判断できない field は、分類に不要であっても NOT_REQUIRED として返さず省略してください。省略された field は「未入力」として扱われます。
                        evidence には結論に至った短い意味的根拠を記載してください。JSON だけを返してください。

                        USER_TEXT: %s
                        TAXONOMY: %s
                        ALLOWED_FIELDS: %s
                        """
                : """
                        你是设备维保报障输入解析器。请结合整段语义、否定关系、时态和前后对比理解故障症状。
                        problemTypeCode 只能从 TAXONOMY 的 code 中选择。应积极选择与症状语义最接近的既有类别，只有完全不存在合理候选时才返回 UNCLASSIFIED。
                        TAXONOMY 中的 aliases、modelScopes、errorCodes 仅是语义理解的辅助信息；不得因缺少型号、错误码或缺少完全一致的关键词而放弃分类。
                        fields 只能包含 ALLOWED_FIELDS，status 只能为 PRESENT、ABSENT。
                        “未显示错误码”“当前运行没有问题”等明确陈述应识别为 ABSENT 或正常的 PRESENT，而不是 MISSING。
                        原文无法判断的字段，即使对当前分类没有补充价值，也不要返回 NOT_REQUIRED，直接省略；省略字段会被视为“尚未补充”，不得编造事实。
                        evidence 填写得出结论的简短语义依据。
                        仅返回 JSON：{"problemTypeCode":"...","classificationConfidence":0-1,"classificationReason":"...","fields":{"field":{"value":"...","status":"PRESENT|ABSENT","confidence":0-1,"evidence":"..."}}}。

                        USER_TEXT: %s
                        TAXONOMY: %s
                        ALLOWED_FIELDS: %s
                        """).formatted(originalText, writeJson(taxonomy), writeJson(allowedFields));
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.openai().chatModel(),
                    "store", false,
                    "text", Map.of("format", semanticUnderstandingFormat(allowedFields)),
                    "input", prompt);
            JsonNode response = client.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String output = responseText(response);
            if (output.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(output, SemanticProblemUnderstandingResponse.class));
        } catch (Exception exception) {
            // 语义理解是增强能力；所有网络或格式错误均由上层回退到规则结果。
            log.warn("OpenAI problem understanding request failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String responseText(JsonNode response) {
        if (response == null) {
            return "";
        }
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                String text = content.path("text").asText("");
                if (!text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private Map<String, Object> semanticUnderstandingFormat(List<String> allowedFields) {
        Map<String, Object> fieldProperties = new LinkedHashMap<>();
        for (String field : allowedFields) {
            fieldProperties.put(field, Map.of("anyOf", List.of(
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "value", Map.of("type", "string"),
                                    "status", Map.of("type", "string", "enum", List.of("PRESENT", "ABSENT")),
                                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                                    "evidence", Map.of("type", "string")),
                            "required", List.of("value", "status", "confidence", "evidence"),
                            "additionalProperties", false),
                    Map.of("type", "null"))));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "problemTypeCode", Map.of("type", "string"),
                "classificationConfidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                "classificationReason", Map.of("type", "string"),
                "fields", Map.of(
                        "type", "object",
                        "properties", fieldProperties,
                        "required", allowedFields,
                        "additionalProperties", false)));
        schema.put("required", List.of(
                "problemTypeCode", "classificationConfidence", "classificationReason", "fields"));
        schema.put("additionalProperties", false);
        return Map.of(
                "type", "json_schema",
                "name", "problem_understanding",
                "strict", true,
                "schema", schema);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private boolean hasApiKey(RepairAssistantProperties value) {
        return value.openai().apiKey() != null && !value.openai().apiKey().isBlank();
    }
}
