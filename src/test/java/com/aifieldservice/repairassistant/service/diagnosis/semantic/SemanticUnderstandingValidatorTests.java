package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.integration.openai.SemanticProblemUnderstandingResponse;
import com.aifieldservice.repairassistant.integration.openai.SemanticProblemUnderstandingResponse.SemanticField;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemTypeDefinition;
import tools.jackson.databind.ObjectMapper;

class SemanticUnderstandingValidatorTests {

    private final SemanticUnderstandingValidator validator = new SemanticUnderstandingValidator(properties());

    @Test
    void acceptsContextualJapaneseInferenceWithoutLiteralEvidenceSubstring() {
        String text = """
                機種はFH1-AAC
                エバポレーターの霜取りが完全にされない。前回は正常だったが、今回は霜が残っている。
                エラーコードは表示されていない様子。現在冷却操作も問題はない。
                """;
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "DEFROST_FAILURE_FROST", 0.91, "霜取り後も霜が残るため",
                Map.of(
                        "errorCode", new SemanticField("表示なし", "ABSENT", 0.94, "エラー表示がないとの説明"),
                        "operatingState", new SemanticField("冷却運転は正常", "PRESENT", 0.88, "冷却機能自体は正常との文脈"),
                        "occurrence", new SemanticField("前回正常・今回発生", "PRESENT", 0.86, "前回と今回の対比"),
                        "measurement", new SemanticField(null, "NOT_REQUIRED", 0.80, "初期分類には測定値が不要")));

        assertThat(validator.validate(response, text, List.of(taxonomy())).orElseThrow().fields())
                .containsKeys("errorCode", "operatingState", "occurrence")
                .doesNotContainKey("measurement");
    }

    @Test
    void stillRejectsFieldsOutsideControlledWhitelist() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "DEFROST_FAILURE_FROST", 0.90, "reason",
                Map.of("repairAction", new SemanticField("交換", "PRESENT", 0.90, "推測")));

        assertThat(validator.validate(response, "霜が残る", List.of(taxonomy())).orElseThrow().fields())
                .isEmpty();
    }

    @Test
    void rejectsUnavailableValuePlaceholdersSoFieldsRemainMissing() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "DEFROST_FAILURE_FROST", 0.90, "reason",
                Map.of(
                        "measurement", new SemanticField("无可用测量数据", "PRESENT", 0.90, "原文未提供测量值"),
                        "environment", new SemanticField("无环境信息", "PRESENT", 0.90, "原文未提供环境信息")));

        assertThat(validator.validate(response, "蒸发器除霜不完全", List.of(taxonomy())).orElseThrow().fields())
                .doesNotContainKeys("measurement", "environment");
    }

    @Test
    void keepsValidClassificationWhenSomeSupplementalFieldsAreInvalid() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "defrost_failure_frost", 0.52, "霜が残るため除霜異常に近い",
                Map.of("repairAction", new SemanticField("交換", "PRESENT", 0.90, "推測")));

        assertThat(validator.validate(response, "霜が残る", List.of(taxonomy())).orElseThrow().problemTypeCode())
                .isEqualTo("DEFROST_FAILURE_FROST");
    }

    @Test
    void acceptsTheExistingJapaneseCategoryNameWhenTheModelDoesNotReturnItsCode() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "デフロスト不良・着霜", 0.12, "霜取り後も霜が残っている",
                Map.of());

        assertThat(validator.validate(response, "霜が残っている", List.of(taxonomy())).orElseThrow().problemTypeCode())
                .isEqualTo("DEFROST_FAILURE_FROST");
    }

    @Test
    void canonicalizesJapaneseAliasWithDifferentSeparatorToItsCategoryCode() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "デフロスト不良/着霜", 0.90, "reason", Map.of());

        assertThat(validator.validate(response, "霜が残っている", List.of(taxonomy())).orElseThrow().problemTypeCode())
                .isEqualTo("DEFROST_FAILURE_FROST");
    }

    @Test
    void canonicalizesReviewedSourceLabelToItsCategoryCode() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "デフロスト不良/着霜", 0.90, "reason", Map.of());
        ProblemTypeDefinition taxonomy = new ProblemTypeDefinition(
                1, "DEFROST_FAILURE_FROST", "除霜不良 / 着霜", "デフロスト不良・着霜",
                List.of("デフロスト不良/着霜"), List.of(), List.of("FH1-AAC"), List.of(), List.of());

        assertThat(validator.validate(response, "霜が残っている", List.of(taxonomy)).orElseThrow().problemTypeCode())
                .isEqualTo("DEFROST_FAILURE_FROST");
    }

    @Test
    void retainsClassificationWhenAnOptionalLlmFieldHasNullConfidence() throws Exception {
        SemanticProblemUnderstandingResponse response = new ObjectMapper().readValue("""
                {"problemTypeCode":"DEFROST_FAILURE_FROST","classificationConfidence":0.72,
                 "classificationReason":"霜が残っている",
                 "fields":{"errorCode":{"value":"表示なし","status":"ABSENT","confidence":null,"evidence":"明示あり"}}}
                """, SemanticProblemUnderstandingResponse.class);

        SemanticProblemUnderstandingResponse validated = validator
                .validate(response, "霜が残っている", List.of(taxonomy()))
                .orElseThrow();
        assertThat(validated.problemTypeCode()).isEqualTo("DEFROST_FAILURE_FROST");
        assertThat(validated.fields()).isEmpty();
    }

    @Test
    void usesConservativeDefaultWhenLlmOmitsClassificationConfidence() throws Exception {
        SemanticProblemUnderstandingResponse response = new ObjectMapper().readValue("""
                {"problemTypeCode":"DEFROST_FAILURE_FROST","classificationConfidence":null,
                 "classificationReason":"霜が残っている","fields":{}}
                """, SemanticProblemUnderstandingResponse.class);

        SemanticProblemUnderstandingResponse validated = validator
                .validate(response, "霜が残っている", List.of(taxonomy()))
                .orElseThrow();
        assertThat(validated.problemTypeCode()).isEqualTo("DEFROST_FAILURE_FROST");
        assertThat(validated.classificationConfidence()).isEqualTo(0.70);
    }

    @Test
    void usesConservativeDefaultWhenLlmConfidenceIsOutOfRange() {
        SemanticProblemUnderstandingResponse response = new SemanticProblemUnderstandingResponse(
                "DEFROST_FAILURE_FROST", 1.20, "reason", Map.of());

        assertThat(validator.validate(response, "霜が残っている", List.of(taxonomy())).orElseThrow()
                .classificationConfidence()).isEqualTo(0.70);
    }

    private ProblemTypeDefinition taxonomy() {
        return new ProblemTypeDefinition(
                1, "DEFROST_FAILURE_FROST", "除霜不良 / 着霜", "デフロスト不良・着霜",
                List.of(), List.of(), List.of("FH1-AAC"), List.of(), List.of());
    }

    private RepairAssistantProperties properties() {
        return new RepairAssistantProperties(
                new RepairAssistantProperties.Web(List.of()),
                new RepairAssistantProperties.Knowledge("", "", false),
                new RepairAssistantProperties.Qdrant("", "", ""),
                new RepairAssistantProperties.OpenAi("", "", "", "", 0),
                new RepairAssistantProperties.ProblemUnderstanding(true, 70, 15, 0.0, true));
    }
}
