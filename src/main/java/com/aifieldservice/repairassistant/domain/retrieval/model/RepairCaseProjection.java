package com.aifieldservice.repairassistant.domain.retrieval.model;

/** 已发布维修案例投影的读取模型，供检索和证据组装使用。 */
public record RepairCaseProjection(
        String receptionId,
        String model,
        String problemTypeCode,
        String problemTypeLabel,
        String errorCodesJson,
        String complaint,
        String onsiteObservation,
        String causeText,
        String actionText,
        Boolean finalResolved,
        Boolean firstFix,
        int visitCount,
        int totalDurationMinutes,
        String partsJson,
        String sourceReference,
        String trustLevel) {
}
