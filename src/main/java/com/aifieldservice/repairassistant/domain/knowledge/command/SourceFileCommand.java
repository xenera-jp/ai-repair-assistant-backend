package com.aifieldservice.repairassistant.domain.knowledge.command;

/** 登记知识源文件、解析器版本和内容哈希的命令对象。 */
public record SourceFileCommand(long knowledgeBaseId, long ingestionBatchId,
        String logicalDocumentKey, String originalFileName, String fileType,
        String sourceKind, String languageCode, String sha256, long fileSizeBytes,
        String parserVersion, boolean refreshRegistration) {
}
