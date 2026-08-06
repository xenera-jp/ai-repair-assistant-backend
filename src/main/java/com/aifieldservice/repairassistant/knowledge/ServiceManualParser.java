package com.aifieldservice.repairassistant.knowledge;

import java.io.IOException;
import java.nio.file.Path;

import com.aifieldservice.repairassistant.knowledge.ServiceManualKnowledge.ManualDocument;

/**
 * 一个经过业务审查的服务手册解析 Profile。
 *
 * <p>每个实现只声明自己能够识别的固定手册，并在版式或关键文本变化时明确失败。
 * 这让导入器可以统一调度多本手册，同时避免使用一个“万能 PDF Parser”静默地产生错误知识。
 */
public interface ServiceManualParser {

    /** 当前 Profile 是否负责这份文件。文件名只是第一道确定性路由。 */
    boolean supports(Path path);

    /** 解析原始 PDF，并返回可发布、可追溯的标准知识单元。 */
    ManualDocument parse(Path path) throws IOException;
}
