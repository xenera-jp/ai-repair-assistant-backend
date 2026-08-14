package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 建议提前准备的备件及其证据关联。
 * @param partNumber 备件料号
 * @param name 备件名称
 * @param preparationLevel 建议准备等级
 * @param evidenceIds 支撑备件建议的证据标识
 */
public record PartRecommendation(String partNumber, String name, String preparationLevel, List<String> evidenceIds) {}
