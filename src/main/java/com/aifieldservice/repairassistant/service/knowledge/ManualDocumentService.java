package com.aifieldservice.repairassistant.service.knowledge;

import java.nio.file.Path;



/** 提供服务手册文件的受控定位能力。 */
public interface ManualDocumentService {

    /** 校验手册知识记录存在，并返回其可安全读取的物理文件信息。 */
    ManualDocument requireDocument(long manualKnowledgeId);

    /** 已定位手册的标识、原始文件名与本地路径。 */
    public record ManualDocument(long id, String fileName, Path path) {
    }
}
