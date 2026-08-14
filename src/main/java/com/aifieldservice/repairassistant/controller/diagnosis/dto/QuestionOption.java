package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param code 提交时使用的选项编码
 * @param label 展示给用户的选项文本
 */
public record QuestionOption(String code, String label) {
}
