package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 现场诊断为缩小候选范围而提出的一道结构化追问。
 * @param id 问题唯一标识
 * @param type 回答类型，如枚举、文本或数值
 * @param prompt 展示给技术人员的问题文案
 * @param signalCode 本问题要确认的业务信号编码
 * @param candidateCode 该问题主要用于区分的候选编码
 * @param round 当前现场追问轮次
 * @param unit 数值型问题的单位；其他类型为空
 * @param options 枚举型问题的可选答案；其他类型为空
 */
public record OnsiteQuestion(String id, String type, String prompt, String signalCode, String candidateCode, int round, String unit, List<QuestionOption> options) {}
