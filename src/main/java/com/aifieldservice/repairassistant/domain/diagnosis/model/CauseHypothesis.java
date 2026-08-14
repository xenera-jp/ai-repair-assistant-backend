package com.aifieldservice.repairassistant.domain.diagnosis.model;

/**
 * 故障原因目录中的候选假设，包含多语言名称和默认排序。
 * @param code 原因稳定编码
 * @param nameZh 中文名称
 * @param nameJa 日文名称
 * @param defaultRank 目录配置的默认显示顺序
 */
public record CauseHypothesis(String code, String nameZh, String nameJa, int defaultRank) {
}
