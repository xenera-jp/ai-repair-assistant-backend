package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param id 证据标识
 * @param title 证据标题
 * @param sourceReference 来源引用
 * @param summary 证据摘要
 * @param trustLabel 可信等级
 * @param matchedSignals 匹配到的故障信号
 * @param sourceDocument 手册来源定位；非手册证据为空
 */
public record EvidenceItem(
        String id,
        String title,
        String sourceReference,
        String summary,
        String trustLabel,
        List<String> matchedSignals,
        SourceDocumentLocation sourceDocument) {
}
