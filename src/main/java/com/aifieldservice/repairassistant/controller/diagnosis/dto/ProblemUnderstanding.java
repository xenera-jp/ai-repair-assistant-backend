package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param id 问题理解标识
 * @param originalText 原始报障文本
 * @param language 业务语言
 * @param summary 报障摘要
 * @param primaryProblemType 主故障类别
 * @param fields 已提取字段
 * @param readyForAnalysis 是否可开始诊断
 * @param blockingMessage 信息不足时的提示
 */
public record ProblemUnderstanding(
        String id,
        String originalText,
        String language,
        String summary,
        ProblemType primaryProblemType,
        List<UnderstoodField> fields,
        boolean readyForAnalysis,
        String blockingMessage) {
}
