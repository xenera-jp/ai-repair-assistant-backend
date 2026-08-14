package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.time.Instant;

/**
 * @param id 报告标识
 * @param sessionId 来源诊断会话标识
 * @param reportName 报告名称
 * @param note 用户备注
 * @param stage 诊断阶段
 * @param diagnosisStatus 保存时的诊断状态
 * @param topCandidate 第一候选名称
 * @param savedAt 报告保存时间
 * @param snapshot 完整诊断快照
 */
public record SavedReport(
        String id,
        String sessionId,
        String reportName,
        String note,
        String stage,
        String diagnosisStatus,
        String topCandidate,
        Instant savedAt,
        DiagnosisSession snapshot) {
}
