package com.aifieldservice.repairassistant.controller.diagnosis.dto;

import java.util.List;

/**
 * @param type 证据来源类型
 * @param label 分组显示名称
 * @param items 本组证据项
 */
public record EvidenceGroup(String type, String label, List<EvidenceItem> items) {
}
