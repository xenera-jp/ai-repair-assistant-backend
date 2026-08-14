package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 枚举型现场问题的可选回答。
 * @param code 提交答案时使用的选项编码
 * @param label 展示给技术人员的选项文案
 */
public record QuestionOption(String code, String label) {}
