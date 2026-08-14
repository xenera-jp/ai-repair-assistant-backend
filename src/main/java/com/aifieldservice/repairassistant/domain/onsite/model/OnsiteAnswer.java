package com.aifieldservice.repairassistant.domain.onsite.model;

/**
 * 归一化后持久化到会话状态 JSON 的现场观察。
 * @param field 被确认的信号或字段编码
 * @param candidateCode 回答主要影响的候选原因编码
 * @param responseType 回答数据类型
 * @param value 用于评分的规范化值
 * @param label 供界面展示的回答文本
 * @param round 答题发生的现场轮次
 * @param questionId 对应的现场问题标识
 */
public record OnsiteAnswer(
        String field,
        String candidateCode,
        String responseType,
        String value,
        String label,
        int round,
        String questionId) {
}
