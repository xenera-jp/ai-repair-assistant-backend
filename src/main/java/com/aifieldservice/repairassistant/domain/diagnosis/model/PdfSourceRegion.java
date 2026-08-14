package com.aifieldservice.repairassistant.domain.diagnosis.model;
/**
 * PDF 页面内证据高亮区域及页面原始尺寸，坐标原点位于左上角。
 * @param x 高亮区域左上角横坐标
 * @param y 高亮区域左上角纵坐标
 * @param width 高亮区域宽度
 * @param height 高亮区域高度
 * @param pageWidth PDF 原始页面宽度
 * @param pageHeight PDF 原始页面高度
 */
public record PdfSourceRegion(double x, double y, double width, double height, double pageWidth, double pageHeight) {}
