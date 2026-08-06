package com.aifieldservice.repairassistant.api;

import java.time.Instant;
import java.util.List;

/**
 * 诊断 API 的稳定传输模型集合。
 *
 * <p>这些 record 是前后端契约，不直接映射数据库表。数据库保存其中部分对象的 JSON
 * 快照，是为了让 V1 能快速保留一次诊断当时看到的完整结果，而不是每次读取都重新计算。
 */
public final class DiagnosisApiModels {

    private DiagnosisApiModels() {
    }

    /** 用户自然语言问题；inheritedSessionId 为后续多轮上下文预留。 */
    public record ProblemUnderstandingRequest(
            String stage,
            String language,
            String originalText,
            String inheritedSessionId) {
    }

    /**
     * 自然语言经过规则抽取和问题分类后的标准问题对象。
     * readyForAnalysis 只由 A 类字段决定，B/C 类字段通过 fields 向前端提示。
     */
    public record ProblemUnderstanding(
            String id,
            String originalText,
            String language,
            String summary,
            ProblemType primaryProblemType,
            List<UnderstoodField> fields,
            boolean readyForAnalysis,
            String blockingMessage) {
    }

    public record ProblemType(String code, String label, double supportScore) {
    }

    /**
     * 单个业务字段的抽取结果。
     * level: A=必需、B=强推荐、C=增强；state 用于驱动前端提示强度。
     */
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

    public record RejectionRequest(
            String onsiteObservation) {
    }

    public record OnsiteRediagnosisRequest(
            String problemUnderstandingId,
            RejectionRequest rejection) {
    }

    /**
     * 一次可恢复的诊断快照。PRE_DEPARTURE 与 ONSITE 共用同一输出结构，
     * 这样前端可以复用候选、证据和建议组件。
     */
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

    /**
     * 候选原因及其证据支持分。supportScore 是规则化支持度，不是校准概率。
     */
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

    /** 一条可追溯证据；sourceReference 应能定位回原始 Excel 行或现场确认。 */
    public record EvidenceItem(
            String id,
            String title,
            String sourceReference,
            String summary,
            String trustLabel,
            List<String> matchedSignals,
            SourceDocumentLocation sourceDocument) {
    }

    /**
     * 官方手册证据的确定性定位信息。
     * manualKnowledgeId 用于受控文件接口，sourceQuote/sourceAnchor 用于原文核对与高亮。
     */
    public record SourceDocumentLocation(
            long manualKnowledgeId,
            String fileName,
            int pdfPage,
            String printedPage,
            String sectionPath,
            String sourceQuote,
            String sourceAnchor,
            PdfSourceRegion sourceRegion) {
    }

    /** Rectangle of the cited text in top-left PDF point coordinates. */
    public record PdfSourceRegion(
            double x,
            double y,
            double width,
            double height,
            double pageWidth,
            double pageHeight) {
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

    /**
     * 从候选原因的 clarification template 生成的单个现场问题。
     * candidateCode 和 signalCode 用来把回答精确反馈到某个候选及其判别信号。
     */
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

    /** 用户主动保存的不可变诊断快照。 */
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
