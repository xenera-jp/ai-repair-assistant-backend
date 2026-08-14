package com.aifieldservice.repairassistant.domain.knowledge.model;

/** 尚待写入向量库的服务手册知识投影。 */
public record PendingManualVector(long id, String model, String problemTypeCode, String errorCode,
        String knowledgeType, String problemProjection, String qdrantPointId) {
}
