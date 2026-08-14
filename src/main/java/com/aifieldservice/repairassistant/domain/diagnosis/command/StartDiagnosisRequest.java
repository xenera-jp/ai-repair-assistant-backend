package com.aifieldservice.repairassistant.domain.diagnosis.command;
/**
 * 启动诊断会话时引用的问题理解结果及字段确认策略。
 * @param problemUnderstandingId 已保存的问题理解标识
 * @param continueWithoutRecommendedFields 是否允许缺少建议字段时继续
 */
public record StartDiagnosisRequest(String problemUnderstandingId, boolean continueWithoutRecommendedFields) {}
