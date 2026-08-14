package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param partNumber 备件料号
 * @param name 备件名称
 * @param preparationLevel 建议准备等级
 * @param evidenceIds 支撑该建议的证据标识
 */
public record PartRecommendation(
        String partNumber,
        String name,
        String preparationLevel,
        List<String> evidenceIds) {
}
