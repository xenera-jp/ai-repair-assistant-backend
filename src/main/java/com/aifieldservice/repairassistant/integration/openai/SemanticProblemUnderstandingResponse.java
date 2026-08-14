package com.aifieldservice.repairassistant.integration.openai;

import java.util.Map;

/** OpenAI 语义理解接口的受限响应；最终采纳仍由领域校验器决定。 */
public record SemanticProblemUnderstandingResponse(
        String problemTypeCode,
        Double classificationConfidence,
        String classificationReason,
        Map<String, SemanticField> fields) {

    /**
     * 语义字段允许归纳上下文含义，不要求依据是原文逐字片段。
     * status 仅允许 PRESENT、ABSENT；无法从原文判断的字段应直接省略，
     * 由服务端保留为 MISSING。
     */
    public record SemanticField(String value, String status, Double confidence, String evidence) {
    }
}
