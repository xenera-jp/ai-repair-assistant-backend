package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param code 字段业务编码
 * @param label 字段显示名称
 * @param value 提取出的字段值
 * @param unit 数值字段单位
 * @param sourceText 支持该字段的原文
 * @param level 字段重要等级
 * @param state 提取或确认状态
 * @param confidence 提取可信度
 * @param prompt 需要确认时的提问文案
 */
public record UnderstoodField(
        String code,
        String label,
        Object value,
        String unit,
        String sourceText,
        String level,
        String state,
        double confidence,
        String prompt) {
}
