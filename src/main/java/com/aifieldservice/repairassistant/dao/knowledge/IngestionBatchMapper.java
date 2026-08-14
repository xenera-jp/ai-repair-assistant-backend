package com.aifieldservice.repairassistant.dao.knowledge;

import org.apache.ibatis.annotations.Param;

/** Shared lifecycle records for Excel and service-manual ingestion batches. */
public interface IngestionBatchMapper {

    int insert(@Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("batchKey") String batchKey,
            @Param("totalFiles") int totalFiles);

    /** 按批次业务键查询数据库主键。 */
    Long findIdByBatchKey(@Param("batchKey") String batchKey);

    /** 将导入批次标记为完成并记录成功处理的记录数。 */
    int complete(@Param("batchId") long batchId,
            @Param("totalRecords") int totalRecords);
}
