package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemMatch;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemTypeDefinition;

class SemanticFallbackPolicyTests {

    private final SemanticFallbackPolicy policy = new SemanticFallbackPolicy(properties());

    @Test
    void acceptsClearRuleClassificationWithoutCallingLlmForOptionalFields() {
        RuleSufficiencyResult result = policy.evaluate(
                List.of(match("REFRIGERATION", 82, List.of("错误码匹配"))), true, true);

        assertThat(result.classificationSufficient()).isTrue();
        assertThat(result.analysisReady()).isTrue();
        assertThat(result.shouldInvokeLlmForClassification()).isFalse();
    }

    @Test
    void asksForRequiredFieldsInsteadOfCallingLlmToGuessThem() {
        RuleSufficiencyResult result = policy.evaluate(List.of(), false, false);

        assertThat(result.analysisReady()).isFalse();
        assertThat(result.shouldInvokeLlmForClassification()).isFalse();
        assertThat(result.reasons()).contains("MISSING_EQUIPMENT_MODEL", "MISSING_MEANINGFUL_SYMPTOM");
    }

    @Test
    void invokesLlmForSemanticClassificationEvenWhenModelIsMissing() {
        RuleSufficiencyResult result = policy.evaluate(List.of(), false, true);

        assertThat(result.analysisReady()).isFalse();
        assertThat(result.shouldInvokeLlmForClassification()).isTrue();
    }

    @Test
    void doesNotInvokeLlmWhenRuleCandidateReachesAnalysisThresholdEvenIfCandidatesAreClose() {
        RuleSufficiencyResult result = policy.evaluate(
                List.of(
                        match("REFRIGERATION", 72, List.of("故障模式精确匹配")),
                        match("DEFROST", 66, List.of("故障模式精确匹配"))),
                true, true);

        assertThat(result.classificationSufficient()).isTrue();
        assertThat(result.shouldInvokeLlmForClassification()).isFalse();
    }

    @Test
    void invokesLlmOnlyWhenNoRuleCandidateReachesAnalysisThreshold() {
        RuleSufficiencyResult result = policy.evaluate(
                List.of(match("UNSUPPORTED", 34, List.of("弱匹配"))), true, true);

        assertThat(result.classificationSufficient()).isFalse();
        assertThat(result.shouldInvokeLlmForClassification()).isTrue();
        assertThat(result.reasons()).contains("TOP1_SCORE_BELOW_THRESHOLD");
    }

    private ProblemMatch match(String code, int score, List<String> signals) {
        return new ProblemMatch(new ProblemTypeDefinition(
                1, code, code, code, List.of(), List.of(), List.of(), List.of(), List.of()), score, signals);
    }

    private RepairAssistantProperties properties() {
        return new RepairAssistantProperties(
                new RepairAssistantProperties.Web(List.of()),
                new RepairAssistantProperties.Knowledge("", "", false),
                new RepairAssistantProperties.Qdrant("", "", ""),
                new RepairAssistantProperties.OpenAi("", "", "", "", 0),
                new RepairAssistantProperties.ProblemUnderstanding(true, 35, 15, 0.70, false));
    }
}
