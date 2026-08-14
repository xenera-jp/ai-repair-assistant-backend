package com.aifieldservice.repairassistant.domain.report.command;
/**
 * 用户主动保存诊断报告时可附带的名称和备注。
 * @param reportName 自定义报告名称；为空时使用系统默认名称
 * @param note 用户填写的补充备注
 */
public record SaveReportRequest(String reportName, String note) {}
