package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.service.knowledge.ProblemCatalogService.ProblemMatch;

/**
 * 将规则评分转成稳定、可测试的模型调用决策。
 *
 * <p>规则候选达到可分析阈值时直接使用规则结果，不调用 LLM。只有规则没有达到该阈值时
 * 才请求语义分类；A 类缺失时先向用户补问，防止模型猜测设备型号或故障事实。
 */
@Component
public class SemanticFallbackPolicy {

    private final RepairAssistantProperties properties;

    public SemanticFallbackPolicy(RepairAssistantProperties properties) {
        this.properties = properties;
    }

    public RuleSufficiencyResult evaluate(
            List<ProblemMatch> candidates,
            boolean hasEquipmentModel,
            boolean hasMeaningfulSymptom) {
        List<String> reasons = new ArrayList<>();
        ProblemMatch top = candidates.isEmpty() ? null : candidates.getFirst();
        int acceptScore = properties.problemUnderstanding().ruleClassificationAcceptScore();

        boolean scoreEnough = top != null && top.score() >= acceptScore;

        if (!scoreEnough) {
            reasons.add("TOP1_SCORE_BELOW_THRESHOLD");
        }
        if (!hasEquipmentModel) {
            reasons.add("MISSING_EQUIPMENT_MODEL");
        }
        if (!hasMeaningfulSymptom) {
            reasons.add("MISSING_MEANINGFUL_SYMPTOM");
        }

        // 候选服务已过滤掉 <35 的弱匹配；达到业务阈值即足以进入后续 AI 分析。
        // 不再以分差或额外强信号触发模型，保证确定性请求走低延迟规则路径。
        boolean classificationSufficient = scoreEnough;
        boolean analysisReady = classificationSufficient && hasEquipmentModel && hasMeaningfulSymptom;
        boolean shouldInvokeLlm = properties.problemUnderstanding().semanticFallbackEnabled()
                && !classificationSufficient
                && hasMeaningfulSymptom;
        return new RuleSufficiencyResult(
                classificationSufficient, analysisReady, shouldInvokeLlm, List.copyOf(reasons));
    }

}
