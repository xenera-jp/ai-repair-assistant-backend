package com.aifieldservice.repairassistant.domain.report.model;
import java.time.Instant;
import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
/** 面向调用方的已保存报告视图，包含可直接展示的诊断快照。 */
public record SavedReport(String id, String sessionId, String reportName, String note, String stage, String diagnosisStatus, String topCandidate, Instant savedAt, DiagnosisSession snapshot) {}
