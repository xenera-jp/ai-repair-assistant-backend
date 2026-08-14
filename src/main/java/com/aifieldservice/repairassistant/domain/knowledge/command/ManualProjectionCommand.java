package com.aifieldservice.repairassistant.domain.knowledge.command;

/** 将已解析的手册知识单元发布到检索投影表时使用的写入载荷。 */
public record ManualProjectionCommand(long versionId, String documentName, String manufacturer, String model,
        String problemTypeCode, String knowledgeType, String errorCode, String title, String titleJa,
        String summary, String summaryJa, String sourceQuote, String sourceAnchor, String sourceRegionJson,
        String actionStepsJson, String actionStepsJaJson, String safetyWarningsJson, String safetyWarningsJaJson,
        String candidateCodesJson, String sourceReference, int pdfPageIndex, String printedPageLabel,
        String sectionPath, String problemProjection, String problemProjectionJa, String resolutionProjection,
        String resolutionProjectionJa, String pointId) {
}
