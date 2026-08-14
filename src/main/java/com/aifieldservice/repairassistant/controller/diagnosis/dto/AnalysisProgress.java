package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param phase 当前处理阶段代码
 * @param percent 完成百分比，范围 0 至 100
 */
public record AnalysisProgress(String phase, int percent) {
}
