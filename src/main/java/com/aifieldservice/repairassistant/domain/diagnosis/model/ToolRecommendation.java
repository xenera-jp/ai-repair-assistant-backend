package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 建议现场携带或使用的工具。
 * @param code 工具稳定业务编码
 * @param name 工具显示名称
 */
public record ToolRecommendation(String code, String name) {}
