package com.aifieldservice.repairassistant.service.diagnosis.semantic;

import java.util.List;

/**
 * 规则阶段的可用性判定结果。
 *
 * <p>分类充分、满足分析门禁与是否调用模型是独立概念：字段为空不会自动触发模型。
 */
public record RuleSufficiencyResult(
        boolean classificationSufficient,
        boolean analysisReady,
        boolean shouldInvokeLlmForClassification,
        List<String> reasons) {
}
