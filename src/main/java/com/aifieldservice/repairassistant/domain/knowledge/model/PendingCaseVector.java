package com.aifieldservice.repairassistant.domain.knowledge.model;

/** 尚待写入向量库的维修案例投影。 */
public record PendingCaseVector(long id, String receptionId, String model, String problemTypeCode,
        String problemProjection, String qdrantPointId) {
}
