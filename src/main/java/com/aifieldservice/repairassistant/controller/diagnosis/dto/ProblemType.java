package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param code 故障类别编码
 * @param label 类别显示名称
 * @param supportScore 类别匹配得分
 */
public record ProblemType(String code, String label, double supportScore) {
}
