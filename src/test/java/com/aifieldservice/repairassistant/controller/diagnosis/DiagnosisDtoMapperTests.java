package com.aifieldservice.repairassistant.controller.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.controller.diagnosis.dto.OnsiteQuestionResponseRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.StartDiagnosisRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.*;

class DiagnosisDtoMapperTests {

    private final DiagnosisDtoMapper mapper = new DiagnosisDtoMapper();

    @Test
    void mapsHttpRequestsToUseCaseInputsWithoutChangingValues() {
        var understanding = mapper.toDomain(
                new ProblemUnderstandingRequest("ONSITE", "ja-JP", "E6 alarm", "parent-1"));
        var start = mapper.toDomain(new StartDiagnosisRequest("understanding-1", true));
        var answer = mapper.toDomain(
                new OnsiteQuestionResponseRequest("MEASUREMENT", null, null, 12.5, "V"));

        assertThat(understanding.stage()).isEqualTo("ONSITE");
        assertThat(understanding.language()).isEqualTo("ja-JP");
        assertThat(understanding.originalText()).isEqualTo("E6 alarm");
        assertThat(understanding.inheritedSessionId()).isEqualTo("parent-1");
        assertThat(start.problemUnderstandingId()).isEqualTo("understanding-1");
        assertThat(start.continueWithoutRecommendedFields()).isTrue();
        assertThat(answer.responseType()).isEqualTo("MEASUREMENT");
        assertThat(answer.valueNumber()).isEqualTo(12.5);
        assertThat(answer.unit()).isEqualTo("V");
    }

    @Test
    void mapsNestedDomainSessionToExistingResponseShape() {
        var source = new DiagnosisSession(
                "session-1", "ONSITE", "CONVERGED",
                new AnalysisProgress("DONE", 100),
                new ProblemUnderstanding("understanding-1", "E6", "zh-CN", "summary",
                        new ProblemType("FAN_FAILURE", "风机故障", 0.92),
                        List.of(new UnderstoodField("errorCode", "错误码", "E6", null,
                                "E6", "B", "EXTRACTED", 0.99, null)), true, null),
                List.of(new DiagnosisCandidate("FAN", "风机异常", 1, 88,
                        "HIGH", "evidence", List.of("manual-1"))),
                List.of(new EvidenceGroup("MANUAL", "手册", List.of(
                        new EvidenceItem("manual-1", "E6", "manual.pdf", "summary",
                                "高", List.of("E6"), new SourceDocumentLocation(7L,
                                        "manual.pdf", 5, "3", "Alarm", "quote", "anchor",
                                        new PdfSourceRegion(1, 2, 3, 4, 600, 800)))))),
                new Recommendations(
                        List.of(new PartRecommendation("P-1", "fan", "PREPARE", List.of("manual-1"))),
                        List.of(new ToolRecommendation("T-1", "meter")),
                        List.of(new RepairStep(1, "check fan", "manual", List.of("manual-1")))),
                new OnsiteQuestion("q-1", "OPTION", "运行？", "fanRunning", "FAN", 1,
                        null, List.of(new QuestionOption("NO", "否"))),
                Instant.parse("2026-08-11T00:00:00Z"));

        var result = mapper.toDto(source);

        assertThat(result.id()).isEqualTo("session-1");
        assertThat(result.problemUnderstanding().primaryProblemType().code()).isEqualTo("FAN_FAILURE");
        assertThat(result.candidates()).singleElement().extracting(candidate -> candidate.evidenceIds())
                .isEqualTo(List.of("manual-1"));
        assertThat(result.evidenceGroups()).singleElement().satisfies(group -> {
            assertThat(group.items()).singleElement().satisfies(item -> {
                assertThat(item.sourceDocument().manualKnowledgeId()).isEqualTo(7L);
                assertThat(item.sourceDocument().sourceRegion().pageHeight()).isEqualTo(800);
            });
        });
        assertThat(result.recommendations().steps()).singleElement().extracting(step -> step.instruction())
                .isEqualTo("check fan");
        assertThat(result.nextQuestion().options()).singleElement().extracting(option -> option.code())
                .isEqualTo("NO");
    }

    @Test
    void preservesNullOptionalResponseSections() {
        var source = new DiagnosisSession("session-2", "ONSITE", "PENDING",
                new AnalysisProgress("WAITING", 50),
                new ProblemUnderstanding("understanding-2", "text", "zh-CN", "summary",
                        new ProblemType("UNKNOWN", "未知", 0), List.of(), false, "missing"),
                List.of(), List.of(), null, null, Instant.now());

        var result = mapper.toDto(source);

        assertThat(result.recommendations()).isNull();
        assertThat(result.nextQuestion()).isNull();
    }
}
