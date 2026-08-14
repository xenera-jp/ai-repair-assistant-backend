package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param reportName 自定义名称；为空时使用默认名称
 * @param note 报告补充备注
 */
public record SaveReportRequest(String reportName, String note) {
}
