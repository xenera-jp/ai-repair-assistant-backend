package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param problemUnderstandingId 已确认的重新理解结果标识
 * @param rejection 原结论的现场否定信息
 */
public record OnsiteRediagnosisRequest(
        String problemUnderstandingId,
        RejectionRequest rejection) {
}
