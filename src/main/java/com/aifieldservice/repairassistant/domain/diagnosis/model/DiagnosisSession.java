package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.time.Instant;
import java.util.List;
/**
 * 一次出发前或现场诊断的聚合快照，是接口返回和报告固化的核心对象。
 * @param id 诊断会话唯一标识
 * @param stage 诊断阶段，区分出发前与现场
 * @param status 当前会话业务状态和证据充分程度
 * @param progress 当前诊断进度
 * @param problemUnderstanding 该会话使用的问题理解快照
 * @param candidates 已排序的原因候选
 * @param evidenceGroups 按来源组织的检索证据
 * @param recommendations 备件、工具和维修步骤建议
 * @param nextQuestion 现场阶段下一道追问；非现场时为空
 * @param updatedAt 快照生成或最近更新时间
 */
public record DiagnosisSession(String id, String stage, String status, AnalysisProgress progress, ProblemUnderstanding problemUnderstanding, List<DiagnosisCandidate> candidates, List<EvidenceGroup> evidenceGroups, Recommendations recommendations, OnsiteQuestion nextQuestion, Instant updatedAt) {}
