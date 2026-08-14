package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 诊断异步处理的当前阶段及完成百分比。
 * @param phase 当前处理阶段代码
 * @param percent 阶段完成百分比，范围为 0 到 100
 */
public record AnalysisProgress(String phase, int percent) {}
