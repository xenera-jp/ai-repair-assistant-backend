package com.aifieldservice.repairassistant.domain.report.model;

import java.time.Instant;

/** 报告表的持久化记录，保存生成时的诊断快照 JSON。 */
public record SavedDiagnosisReport(String reportKey, String sessionKey, String reportName,
        String note, String stage, String diagnosisStatus, String snapshotJson, Instant createdAt) {
}
