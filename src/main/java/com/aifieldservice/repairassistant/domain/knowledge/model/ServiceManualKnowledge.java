package com.aifieldservice.repairassistant.domain.knowledge.model;

import java.util.List;

/** 服务手册解析器与知识导入器之间的稳定数据契约。 */
public final class ServiceManualKnowledge {

    private ServiceManualKnowledge() {
    }

    /**
     * 一份物理 PDF 的解析结果。
     *
     * <p>logicalDocumentKey 表示业务上的文档身份，parserVersion 表示解析规则版本。
     * 两者与文件 SHA 一起决定是否需要重新构建知识。
     */
    public record ManualDocument(
            String documentName,
            String logicalDocumentKey,
            String parserVersion,
            String manufacturer,
            String model,
            int pageCount,
            List<ManualUnit> units) {
    }

    /** PDF 页面原文。pdfPageIndex 使用从 1 开始的 PDF 页序。 */
    public record PageText(int pdfPageIndex, String printedPageLabel, String text) {
    }

    /** 左上角为原点的 PDF 坐标及页面原始尺寸，供前端绘制证据高亮。 */
    public record SourceRegion(
            double x,
            double y,
            double width,
            double height,
            double pageWidth,
            double pageHeight) {
    }

    /**
     * 可发布的知识单元。
     *
     * <p>problemTypeCode 和 errorCode 属于单元而不是整本手册：同一本手册通常同时包含
     * 多个故障类别，且 Diagnosis Chart 中大量条目没有错误码。
     */
    public record ManualUnit(
            String unitKey,
            String unitType,
            String problemTypeCode,
            String errorCode,
            String title,
            String titleJa,
            String summary,
            String summaryJa,
            String sourceQuote,
            String sourceAnchor,
            SourceRegion sourceRegion,
            List<String> actionSteps,
            List<String> actionStepsJa,
            List<String> safetyWarnings,
            List<String> safetyWarningsJa,
            List<String> candidateCodes,
            PageText sourcePage,
            String sectionPath,
            String problemProjection,
            String problemProjectionJa,
            String resolutionProjection,
            String resolutionProjectionJa) {
    }
}
