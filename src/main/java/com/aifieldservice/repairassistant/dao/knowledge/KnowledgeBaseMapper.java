package com.aifieldservice.repairassistant.dao.knowledge;

import org.apache.ibatis.annotations.Param;

/** Shared access to the logical knowledge-base root. */
public interface KnowledgeBaseMapper {

    int upsertActive(@Param("code") String code, @Param("name") String name);

    Long findIdByCode(@Param("code") String code);
}
