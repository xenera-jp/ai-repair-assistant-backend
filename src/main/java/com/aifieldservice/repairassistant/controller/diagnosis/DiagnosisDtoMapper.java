package com.aifieldservice.repairassistant.controller.diagnosis;

import java.util.List;

import com.aifieldservice.repairassistant.controller.diagnosis.dto.*;

/** Explicit boundary mapping between HTTP DTOs and diagnosis use-case models. */
final class DiagnosisDtoMapper {
    com.aifieldservice.repairassistant.domain.diagnosis.command.ProblemUnderstandingRequest toDomain(ProblemUnderstandingRequest source) {
        return new com.aifieldservice.repairassistant.domain.diagnosis.command.ProblemUnderstandingRequest(source.stage(), source.language(),
                source.originalText(), source.inheritedSessionId());
    }
    com.aifieldservice.repairassistant.domain.diagnosis.command.StartDiagnosisRequest toDomain(StartDiagnosisRequest source) {
        return new com.aifieldservice.repairassistant.domain.diagnosis.command.StartDiagnosisRequest(source.problemUnderstandingId(),
                source.continueWithoutRecommendedFields());
    }
    com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest toDomain(OnsiteQuestionResponseRequest source) {
        return new com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest(source.responseType(), source.selectedOptionCode(),
                source.rawText(), source.valueNumber(), source.unit());
    }
    com.aifieldservice.repairassistant.domain.onsite.command.RejectionRequest toDomain(RejectionRequest source) {
        return new com.aifieldservice.repairassistant.domain.onsite.command.RejectionRequest(source.onsiteObservation());
    }
    com.aifieldservice.repairassistant.domain.onsite.command.OnsiteRediagnosisRequest toDomain(OnsiteRediagnosisRequest source) {
        return new com.aifieldservice.repairassistant.domain.onsite.command.OnsiteRediagnosisRequest(source.problemUnderstandingId(),
                source.rejection() == null ? null : toDomain(source.rejection()));
    }
    com.aifieldservice.repairassistant.domain.report.command.SaveReportRequest toDomain(SaveReportRequest source) {
        return new com.aifieldservice.repairassistant.domain.report.command.SaveReportRequest(source.reportName(), source.note());
    }
    ProblemUnderstanding toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.ProblemUnderstanding source) {
        return new ProblemUnderstanding(source.id(), source.originalText(), source.language(), source.summary(),
                toDto(source.primaryProblemType()), map(source.fields(), this::toDto),
                source.readyForAnalysis(), source.blockingMessage());
    }
    DiagnosisSession toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession source) {
        return new DiagnosisSession(source.id(), source.stage(), source.status(), toDto(source.progress()),
                toDto(source.problemUnderstanding()), map(source.candidates(), this::toDto),
                map(source.evidenceGroups(), this::toDto), toDto(source.recommendations()),
                source.nextQuestion() == null ? null : toDto(source.nextQuestion()), source.updatedAt());
    }
    SavedReport toDto(com.aifieldservice.repairassistant.domain.report.model.SavedReport source) {
        return new SavedReport(source.id(), source.sessionId(), source.reportName(), source.note(), source.stage(),
                source.diagnosisStatus(), source.topCandidate(), source.savedAt(), toDto(source.snapshot()));
    }
    private AnalysisProgress toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.AnalysisProgress source) { return new AnalysisProgress(source.phase(), source.percent()); }
    private ProblemType toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.ProblemType source) { return new ProblemType(source.code(), source.label(), source.supportScore()); }
    private UnderstoodField toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.UnderstoodField source) { return new UnderstoodField(source.code(), source.label(), source.value(), source.unit(), source.sourceText(), source.level(), source.state(), source.confidence(), source.prompt()); }
    private DiagnosisCandidate toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisCandidate source) { return new DiagnosisCandidate(source.code(), source.label(), source.rank(), source.supportScore(), source.supportBand(), source.explanation(), source.evidenceIds()); }
    private EvidenceGroup toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.EvidenceGroup source) { return new EvidenceGroup(source.type(), source.label(), map(source.items(), this::toDto)); }
    private EvidenceItem toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.EvidenceItem source) { return new EvidenceItem(source.id(), source.title(), source.sourceReference(), source.summary(), source.trustLabel(), source.matchedSignals(), source.sourceDocument() == null ? null : toDto(source.sourceDocument())); }
    private SourceDocumentLocation toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.SourceDocumentLocation source) { return new SourceDocumentLocation(source.manualKnowledgeId(), source.fileName(), source.pdfPage(), source.printedPage(), source.sectionPath(), source.sourceQuote(), source.sourceAnchor(), source.sourceRegion() == null ? null : toDto(source.sourceRegion())); }
    private PdfSourceRegion toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.PdfSourceRegion source) { return new PdfSourceRegion(source.x(), source.y(), source.width(), source.height(), source.pageWidth(), source.pageHeight()); }
    private Recommendations toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.Recommendations source) { return source == null ? null : new Recommendations(map(source.parts(), this::toDto), map(source.tools(), this::toDto), map(source.steps(), this::toDto)); }
    private PartRecommendation toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.PartRecommendation source) { return new PartRecommendation(source.partNumber(), source.name(), source.preparationLevel(), source.evidenceIds()); }
    private ToolRecommendation toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.ToolRecommendation source) { return new ToolRecommendation(source.code(), source.name()); }
    private RepairStep toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.RepairStep source) { return new RepairStep(source.sequence(), source.instruction(), source.sourceLabel(), source.evidenceIds()); }
    private OnsiteQuestion toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.OnsiteQuestion source) { return new OnsiteQuestion(source.id(), source.type(), source.prompt(), source.signalCode(), source.candidateCode(), source.round(), source.unit(), map(source.options(), this::toDto)); }
    private QuestionOption toDto(com.aifieldservice.repairassistant.domain.diagnosis.model.QuestionOption source) { return new QuestionOption(source.code(), source.label()); }
    private <S, T> List<T> map(List<S> source, java.util.function.Function<S, T> converter) {
        return source == null ? null : source.stream().map(converter).toList();
    }
}
