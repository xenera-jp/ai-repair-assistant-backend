package com.aifieldservice.repairassistant.domain.diagnosis.model;
import java.util.List;
/**
 * 按来源或用途归类的一组诊断证据。
 * @param type 证据来源类型
 * @param label 当前语言下的分组名称
 * @param items 本分组包含的证据项
 */
public record EvidenceGroup(String type, String label, List<EvidenceItem> items) {}
