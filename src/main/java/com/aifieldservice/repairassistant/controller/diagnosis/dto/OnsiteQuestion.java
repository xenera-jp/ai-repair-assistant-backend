package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param id 问题标识
 * @param type 回答类型
 * @param prompt 问题文案
 * @param signalCode 待确认的信号编码
 * @param candidateCode 主要区分的候选编码
 * @param round 现场追问轮次
 * @param unit 数值回答单位
 * @param options 枚举回答选项
 */
public record OnsiteQuestion(
        String id,
        String type,
        String prompt,
        String signalCode,
        String candidateCode,
        int round,
        String unit,
        List<QuestionOption> options) {
}
