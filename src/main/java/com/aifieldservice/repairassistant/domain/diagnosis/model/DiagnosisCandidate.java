package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 针对当前故障描述生成的原因候选及其证据支持情况。
 * @param code 原因假设的稳定业务编码
 * @param label 当前界面语言下的候选名称
 * @param rank 按支持度计算出的显示名次
 * @param supportScore 已检索证据对该候选的支持分，不表示故障概率
 * @param supportBand 支持度分档，如高、中、低
 * @param explanation 面向用户的候选解释
 * @param evidenceIds 支持该候选的证据标识集合
 */
public record DiagnosisCandidate(String code, String label, int rank, double supportScore, String supportBand, String explanation, List<String> evidenceIds) {}
