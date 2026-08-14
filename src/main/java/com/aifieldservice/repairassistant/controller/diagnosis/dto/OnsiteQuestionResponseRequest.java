package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param responseType 回答类型
 * @param selectedOptionCode 枚举选项编码
 * @param rawText 文本回答
 * @param valueNumber 数值回答
 * @param unit 数值单位
 */
public record OnsiteQuestionResponseRequest(
        String responseType,
        String selectedOptionCode,
        String rawText,
        Double valueNumber,
        String unit) {
}
