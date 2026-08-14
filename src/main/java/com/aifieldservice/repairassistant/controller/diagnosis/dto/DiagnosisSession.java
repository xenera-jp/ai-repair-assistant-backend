package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param id 诊断会话标识
 * @param stage 诊断阶段
 * @param status 会话状态
 * @param progress 当前进度
 * @param problemUnderstanding 问题理解结果
 * @param candidates 已排序的原因候选
 * @param evidenceGroups 按来源组织的证据
 * @param recommendations 维修建议
 * @param nextQuestion 下一道现场问题；非现场时为空
 * @param updatedAt 快照更新时间
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
