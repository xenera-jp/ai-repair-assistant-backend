package com.aifieldservice.repairassistant.dao.knowledge;

import org.apache.ibatis.annotations.Param;

/** 保存源文件中可追溯的原始业务记录。 */
public interface SourceRecordMapper {
    /** 登记 Excel 的一行原始记录及其内容指纹。 */
    int insertExcelRow(@Param("sourceFileId") long sourceFileId, @Param("recordType") String recordType,
            @Param("businessKey") String businessKey, @Param("sheetName") String sheetName,
            @Param("sourceRowNo") int sourceRowNo, @Param("rawPayload") String rawPayload,
            @Param("fingerprint") String fingerprint);
    /** 按来源文件、记录类型和业务键查询首条来源记录主键。 */
    Long findFirstId(@Param("sourceFileId") long sourceFileId, @Param("recordType") String recordType,
            @Param("businessKey") String businessKey);
}
