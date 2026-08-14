package com.aifieldservice.repairassistant.domain.knowledge.command;

import java.time.LocalDateTime;

/** 将历史维修工单发布为检索投影时使用的写入载荷。 */
public record RepairCaseProjectionCommand(long versionId, String receptionId, String model, String serialNumber,
        String customerSiteName, LocalDateTime receivedAt, String problemTypeCode, String problemTypeLabel,
        String errorCodesJson, String complaint, String onsiteObservation, String causeText, String actionText,
        boolean finalResolved, boolean firstFix, int visitCount, int totalDurationMinutes, String partsJson,
        String problemProjection, String resolutionProjection, String sourceReference, String pointId,
        String trustLevel) {
}
