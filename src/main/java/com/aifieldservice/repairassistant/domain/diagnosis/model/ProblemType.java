package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 从用户描述中识别出的故障类别及其匹配得分。
 * @param code 故障类别稳定编码
 * @param label 当前语言下的类别名称
 * @param supportScore 类别匹配得分
 */
public record ProblemType(String code, String label, double supportScore) {}
