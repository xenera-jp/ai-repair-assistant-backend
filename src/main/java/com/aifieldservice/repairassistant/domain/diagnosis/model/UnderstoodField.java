package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 从故障描述抽取出的字段、可信度及是否还需要向用户确认。
 * @param code 字段稳定业务编码
 * @param label 当前语言下的字段名称
 * @param value 抽取出的字段值
 * @param unit 数值字段的单位
 * @param sourceText 原文中支持该字段的片段
 * @param level 字段重要级别，用于决定能否开始诊断
 * @param state 提取或确认状态
 * @param confidence 字段提取可信度
 * @param prompt 需要补充或确认时的提问文案
 */
public record UnderstoodField(String code, String label, Object value, String unit, String sourceText, String level, String state, double confidence, String prompt) {}
