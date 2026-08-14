package com.aifieldservice.repairassistant.dao.diagnosis;

import org.apache.ibatis.annotations.Param;

/** MyBatis access point for immutable problem-understanding snapshots. */
public interface ProblemUnderstandingMapper {

    int insert(@Param("understandingKey") String understandingKey,
            @Param("stage") String stage,
            @Param("languageCode") String languageCode,
            @Param("originalText") String originalText,
            @Param("problemTypeCode") String problemTypeCode,
            @Param("readyForAnalysis") boolean readyForAnalysis,
            @Param("payloadJson") String payloadJson);

    /** 按问题理解标识读取完整 JSON 快照；不存在时返回 null。 */
    String findPayloadByUnderstandingKey(@Param("understandingKey") String understandingKey);
}
