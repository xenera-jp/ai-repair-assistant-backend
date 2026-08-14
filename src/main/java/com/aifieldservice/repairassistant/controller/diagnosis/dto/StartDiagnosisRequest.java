package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param problemUnderstandingId 已保存的问题理解标识
 * @param continueWithoutRecommendedFields 是否允许缺少建议字段仍继续
 */
public record StartDiagnosisRequest(
        String problemUnderstandingId,
        boolean continueWithoutRecommendedFields) {
}
