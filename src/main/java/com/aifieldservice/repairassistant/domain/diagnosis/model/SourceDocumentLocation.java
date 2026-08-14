package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * 手册证据的文件、页码、章节及页内坐标定位信息。
 * @param manualKnowledgeId 手册知识单元主键
 * @param fileName 原始手册文件名
 * @param pdfPage PDF 物理页码，从 1 开始
 * @param printedPage 手册印刷页码标签
 * @param sectionPath 手册内章节路径
 * @param sourceQuote 用于高亮的原文摘录
 * @param sourceAnchor 可稳定定位章节的锚点
 * @param sourceRegion PDF 页内高亮区域
 */
public record SourceDocumentLocation(long manualKnowledgeId, String fileName, int pdfPage, String printedPage, String sectionPath, String sourceQuote, String sourceAnchor, PdfSourceRegion sourceRegion) {}
