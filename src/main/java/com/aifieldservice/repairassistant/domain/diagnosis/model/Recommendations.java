package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 一次诊断输出的备件、工具和维修步骤建议集合。
 * @param parts 建议准备的备件
 * @param tools 建议携带或使用的工具
 * @param steps 建议执行的维修步骤
 */
public record Recommendations(List<PartRecommendation> parts, List<ToolRecommendation> tools, List<RepairStep> steps) {}
