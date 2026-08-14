package com.aifieldservice.repairassistant.dao.diagnosis;

import org.apache.ibatis.annotations.Param;

/** Access to immutable and mutable diagnosis-session snapshots. */
public interface DiagnosisSnapshotMapper {

    int insert(@Param("sessionKey") String sessionKey,
            @Param("understandingKey") String understandingKey,
            @Param("stage") String stage,
            @Param("status") String status,
            @Param("payloadJson") String payloadJson);

    /** 按会话标识覆盖诊断快照的阶段、状态和 JSON 负载。 */
    int update(@Param("sessionKey") String sessionKey,
            @Param("stage") String stage,
            @Param("status") String status,
            @Param("payloadJson") String payloadJson);

    /** 查询指定诊断会话的完整 JSON 快照；不存在时返回 null。 */
    String findPayloadBySessionKey(@Param("sessionKey") String sessionKey);

    /** 仅当会话仍处于活动状态时将其标记为已否定，返回实际更新行数。 */
    int rejectIfActive(@Param("sessionKey") String sessionKey,
            @Param("status") String status,
            @Param("payloadJson") String payloadJson);
}
