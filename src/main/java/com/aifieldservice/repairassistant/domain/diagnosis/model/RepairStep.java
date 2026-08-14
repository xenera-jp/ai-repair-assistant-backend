package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 按执行顺序给出的单个维修动作，并关联支持它的证据。
 * @param sequence 在维修流程中的顺序号
 * @param instruction 技术人员执行的操作说明
 * @param sourceLabel 操作说明的来源标签
 * @param evidenceIds 支撑该操作的证据标识
 */
public record RepairStep(int sequence, String instruction, String sourceLabel, List<String> evidenceIds) {}
