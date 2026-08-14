package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param parts 建议准备的备件
 * @param tools 建议使用的工具
 * @param steps 建议执行的维修步骤
 */
public record Recommendations(
        List<PartRecommendation> parts,
        List<ToolRecommendation> tools,
        List<RepairStep> steps) {
}
