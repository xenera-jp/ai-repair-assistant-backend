package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 将自然语言报障转化后的结构化问题理解结果。
 * @param id 问题理解唯一标识
 * @param originalText 用户提交的原始报障文本
 * @param language 原始文本和返回文案使用的语言
 * @param summary 对报障的简要归纳
 * @param primaryProblemType 识别出的主故障类别
 * @param fields 已抽取的结构化字段
 * @param readyForAnalysis 是否具备启动诊断所需的最小信息
 * @param blockingMessage 信息不足时提示用户补充的原因
 */
public record ProblemUnderstanding(String id, String originalText, String language, String summary, ProblemType primaryProblemType, List<UnderstoodField> fields, boolean readyForAnalysis, String blockingMessage) {}
