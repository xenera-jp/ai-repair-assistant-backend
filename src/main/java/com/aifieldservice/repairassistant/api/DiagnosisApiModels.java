package com.aifieldservice.repairassistant.api;

import java.time.Instant;
import java.util.List;

public final class DiagnosisApiModels {

    private DiagnosisApiModels() {
    }

    public record ProblemUnderstandingRequest(
            String stage,
            String language,
            String originalText,
            String inheritedSessionId) {
    }

    public record ProblemUnderstanding(
            String id,
            String originalText,
            String summary,
            ProblemType primaryProblemType,
            List<UnderstoodField> fields,
            boolean readyForAnalysis,
            String blockingMessage) {
    }

    public record ProblemType(String code, String label, double supportScore) {
    }

    public record UnderstoodField(
            String code,
            String label,
            Object value,
            String unit,
            String sourceText,
            String level,
            String state,
            double confidence,
            String prompt) {
    }

    public record StartDiagnosisRequest(
            String problemUnderstandingId,
            boolean continueWithoutRecommendedFields) {
    }

    public record DiagnosisSession(
            String id,
            String stage,
            String status,
            AnalysisProgress progress,
            ProblemUnderstanding problemUnderstanding,
            List<DiagnosisCandidate> candidates,
            List<EvidenceGroup> evidenceGroups,
            Recommendations recommendations,
            OnsiteQuestion nextQuestion,
            Instant updatedAt) {
    }

    public record AnalysisProgress(String phase, int percent) {
    }

    public record DiagnosisCandidate(
            String code,
            String label,
            int rank,
            double supportScore,
            String supportBand,
            String explanation,
            List<String> evidenceIds) {
    }

    public record EvidenceGroup(
            String type,
            String label,
            List<EvidenceItem> items) {
    }

    public record EvidenceItem(
            String id,
            String title,
            String sourceReference,
            String summary,
            String trustLabel,
            List<String> matchedSignals) {
    }

    public record Recommendations(
            List<PartRecommendation> parts,
            List<ToolRecommendation> tools,
            List<RepairStep> steps) {
    }

    public record PartRecommendation(
            String partNumber,
            String name,
            String preparationLevel,
            List<String> evidenceIds) {
    }

    public record ToolRecommendation(String code, String name) {
    }

    public record RepairStep(
            int sequence,
            String instruction,
            String sourceLabel,
            List<String> evidenceIds) {
    }

    public record OnsiteQuestion(
            String id,
            String type,
            String prompt,
            String signalCode,
            String candidateCode,
            int round,
            String unit,
            List<QuestionOption> options) {
    }

    public record QuestionOption(String code, String label) {
    }

    public record OnsiteQuestionResponseRequest(
            String responseType,
            String selectedOptionCode,
            String rawText,
            Double valueNumber,
            String unit) {
    }

    public record SaveReportRequest(String reportName, String note) {
    }

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
}
