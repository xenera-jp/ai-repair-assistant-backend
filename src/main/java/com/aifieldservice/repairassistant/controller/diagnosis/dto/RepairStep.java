package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param sequence 操作顺序号
 * @param instruction 维修操作说明
 * @param sourceLabel 操作来源标签
 * @param evidenceIds 支撑操作的证据标识
 */
public record RepairStep(
        int sequence,
        String instruction,
        String sourceLabel,
        List<String> evidenceIds) {
}
