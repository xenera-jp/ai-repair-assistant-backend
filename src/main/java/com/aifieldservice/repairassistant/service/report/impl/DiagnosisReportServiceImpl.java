package com.aifieldservice.repairassistant.service.report.impl;

import com.aifieldservice.repairassistant.service.report.*;
import com.aifieldservice.repairassistant.service.report.DiagnosisReportService.*;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
import com.aifieldservice.repairassistant.domain.diagnosis.model.ProblemUnderstanding;
import com.aifieldservice.repairassistant.domain.report.command.SaveReportRequest;
import com.aifieldservice.repairassistant.domain.report.model.SavedReport;
import com.aifieldservice.repairassistant.dao.report.SavedDiagnosisReportMapper;
import com.aifieldservice.repairassistant.domain.report.model.SavedDiagnosisReport;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService;

import tools.jackson.databind.ObjectMapper;

/** Saves and reads immutable diagnosis-report snapshots. */
@Service
public class DiagnosisReportServiceImpl implements DiagnosisReportService {

    private final DiagnosisService diagnosisService;
    private final SavedDiagnosisReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    public DiagnosisReportServiceImpl(
            DiagnosisService diagnosisService,
            SavedDiagnosisReportMapper reportMapper,
            ObjectMapper objectMapper) {
        this.diagnosisService = diagnosisService;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SavedReport saveReport(String sessionId, SaveReportRequest request) {
        DiagnosisSession session = diagnosisService.get(sessionId);
        if ("REJECTED".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rejected onsite diagnosis sessions are terminal.");
        }
        if (session.candidates().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前没有可保存的诊断候选。");
        }

        SavedDiagnosisReport existing = reportMapper.findBySessionKey(sessionId);
        if (existing != null) {
            return toSavedReport(existing);
        }

        String model = stringField(session.problemUnderstanding(), "equipmentModel");
        String defaultName = "%s · %s".formatted(
                model,
                session.problemUnderstanding().primaryProblemType().label());
        String reportName = request.reportName() == null || request.reportName().isBlank()
                ? defaultName
                : request.reportName().strip();
        String reportId = UUID.randomUUID().toString();
        reportMapper.insert(reportId, sessionId, reportName, request.note(),
                session.stage(), session.status(), objectMapper.writeValueAsString(session));
        return getReport(reportId);
    }

    @Transactional(readOnly = true)
    public List<SavedReport> listReports() {
        return reportMapper.findAll().stream().map(this::toSavedReport).toList();
    }

    @Transactional(readOnly = true)
    public SavedReport getReport(String reportId) {
        SavedDiagnosisReport report = reportMapper.findByReportKey(reportId);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断报告不存在。");
        }
        return toSavedReport(report);
    }

    private SavedReport toSavedReport(SavedDiagnosisReport row) {
        DiagnosisSession snapshot = objectMapper.readValue(row.snapshotJson(), DiagnosisSession.class);
        String topCandidate = snapshot.candidates().isEmpty() ? null : snapshot.candidates().get(0).label();
        return new SavedReport(row.reportKey(), row.sessionKey(), row.reportName(), row.note(),
                row.stage(), row.diagnosisStatus(), topCandidate, row.createdAt(), snapshot);
    }

    private String stringField(ProblemUnderstanding understanding, String code) {
        return understanding.fields().stream()
                .filter(field -> field.code().equals(code))
                .map(field -> field.value() == null ? "" : String.valueOf(field.value()))
                .findFirst()
                .orElse("");
    }
}
