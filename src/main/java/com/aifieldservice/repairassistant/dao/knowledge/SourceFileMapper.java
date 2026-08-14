package com.aifieldservice.repairassistant.dao.knowledge;

import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.knowledge.command.SourceFileCommand;

/** Shared source-file registry used by every import format. */
public interface SourceFileMapper {

    int upsert(SourceFileCommand command);

    Long findIdByKnowledgeBaseAndSha(@Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("sha256") String sha256);

    /** 标记指定源文件已通过解析和内容校验。 */
    int markValidated(@Param("sourceFileId") long sourceFileId);

    /** 标记指定导入批次下的全部源文件已完成校验。 */
    int markBatchValidated(@Param("batchId") long batchId);

}
