package com.aifieldservice.repairassistant.domain.retrieval.model;

/** 已发布服务手册投影的读取模型；JSON 字段保留列表和页内区域等复杂结构。 */
public record ManualKnowledgeProjection(
        long id,
        String documentName,
        String model,
        String problemTypeCode,
        String knowledgeType,
        String errorCode,
        String title,
        String titleJa,
        String summary,
        String summaryJa,
        String sourceQuote,
        String sourceAnchor,
        String sourceRegionJson,
        String actionStepsJson,
        String actionStepsJaJson,
        String safetyWarningsJson,
        String safetyWarningsJaJson,
        String candidateCodesJson,
        String sourceReference,
        Integer pdfPageIndex,
        String printedPageLabel,
        String sectionPath,
        String trustLevel) {
}
