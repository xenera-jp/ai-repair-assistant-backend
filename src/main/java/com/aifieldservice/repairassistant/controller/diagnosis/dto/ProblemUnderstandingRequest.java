package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param stage 请求所在诊断阶段
 * @param language 用户输入语言
 * @param originalText 原始报障描述
 * @param inheritedSessionId 继承的父会话标识
 */
public record ProblemUnderstandingRequest(
        String stage,
        String language,
        String originalText,
        String inheritedSessionId) {
}
