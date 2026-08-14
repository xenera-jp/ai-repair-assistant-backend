package com.aifieldservice.repairassistant.dao.retrieval;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.aifieldservice.repairassistant.domain.retrieval.model.ManualKnowledgeProjection;

/** Read-only retrieval over published manual knowledge projections. */
public interface ManualKnowledgeProjectionMapper {

    List<ManualKnowledgeProjection> findStructuredManuals(@Param("model") String model,
            @Param("problemTypeCode") String problemTypeCode,
            @Param("errorCode") String errorCode);

    /** 按知识投影主键批量读取手册证据，用于补全向量检索召回结果。 */
    List<ManualKnowledgeProjection> findByIds(@Param("ids") List<Long> ids);
}
