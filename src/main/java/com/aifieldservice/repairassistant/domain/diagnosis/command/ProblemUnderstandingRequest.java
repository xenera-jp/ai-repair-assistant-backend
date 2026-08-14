package com.aifieldservice.repairassistant.domain.diagnosis.command;
/**
 * 将用户故障描述提交给问题理解用例的输入参数。
 * @param stage 发起理解的业务阶段
 * @param language 用户输入和返回内容的语言
 * @param originalText 用户原始报障描述
 * @param inheritedSessionId 现场重分析时继承的父会话标识
 */
public record ProblemUnderstandingRequest(String stage, String language, String originalText, String inheritedSessionId) {}
