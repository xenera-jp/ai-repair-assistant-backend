package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 支撑候选结论的最小证据单元，并可定位回原始手册。
 * @param id 证据唯一标识
 * @param title 证据标题
 * @param sourceReference 来源系统中的可读引用
 * @param summary 用于诊断解释的摘要
 * @param trustLabel 来源可信等级标签
 * @param matchedSignals 与当前报障匹配的信号
 * @param sourceDocument 手册来源的精确定位；非手册证据可为空
 */
public record EvidenceItem(String id, String title, String sourceReference, String summary, String trustLabel, List<String> matchedSignals, SourceDocumentLocation sourceDocument) {}
