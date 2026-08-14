package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param code 原因假设编码
 * @param label 候选显示名称
 * @param rank 候选排序名次
 * @param supportScore 证据支持分，不代表故障概率
 * @param supportBand 支持度分档
 * @param explanation 候选结论说明
 * @param evidenceIds 支撑候选的证据标识
 */
public record DiagnosisCandidate(
        String code,
        String label,
        int rank,
        double supportScore,
        String supportBand,
        String explanation,
        List<String> evidenceIds) {
}
