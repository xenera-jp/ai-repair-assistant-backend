package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param manualKnowledgeId 手册知识单元主键
 * @param fileName 原始手册文件名
 * @param pdfPage PDF 物理页码
 * @param printedPage 印刷页码标签
 * @param sectionPath 手册章节路径
 * @param sourceQuote 原文摘录
 * @param sourceAnchor 稳定章节锚点
 * @param sourceRegion PDF 页内高亮位置
 */
public record SourceDocumentLocation(
        long manualKnowledgeId,
        String fileName,
        int pdfPage,
        String printedPage,
        String sectionPath,
        String sourceQuote,
        String sourceAnchor,
        PdfSourceRegion sourceRegion) {
}
