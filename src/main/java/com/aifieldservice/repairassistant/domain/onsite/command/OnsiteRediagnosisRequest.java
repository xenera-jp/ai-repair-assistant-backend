package com.aifieldservice.repairassistant.domain.onsite.command;
/**
 * 基于现场否定信息重新发起诊断时的输入。
 * @param problemUnderstandingId 已确认的重新理解结果标识
 * @param rejection 原结论被否定时的现场观察
 */
public record OnsiteRediagnosisRequest(String problemUnderstandingId, RejectionRequest rejection) {}
