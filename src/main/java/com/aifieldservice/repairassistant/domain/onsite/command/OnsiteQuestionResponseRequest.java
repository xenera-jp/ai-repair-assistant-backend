package com.aifieldservice.repairassistant.domain.onsite.command;
/**
 * 提交现场追问答案的输入，兼容选项、文本和数值三种回答。
 * @param responseType 回答类型
 * @param selectedOptionCode 枚举型回答的选项编码
 * @param rawText 文本型回答原文
 * @param valueNumber 数值型回答的数值
 * @param unit 数值型回答的单位
 */
public record OnsiteQuestionResponseRequest(String responseType, String selectedOptionCode, String rawText, Double valueNumber, String unit) {}
