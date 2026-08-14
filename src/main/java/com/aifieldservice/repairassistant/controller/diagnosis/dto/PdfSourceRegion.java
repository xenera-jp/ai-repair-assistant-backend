package com.aifieldservice.repairassistant.controller.diagnosis.dto;

/**
 * @param x 高亮区域横坐标
 * @param y 高亮区域纵坐标
 * @param width 高亮区域宽度
 * @param height 高亮区域高度
 * @param pageWidth 原始 PDF 页宽
 * @param pageHeight 原始 PDF 页高
 */
public record PdfSourceRegion(
        double x,
        double y,
        double width,
        double height,
        double pageWidth,
        double pageHeight) {
}
