package com.aifieldservice.repairassistant.dao.knowledge;
import org.apache.ibatis.annotations.Param;
/** 维护知识版本与原始来源记录关联关系的 MyBatis Mapper。 */
public interface KnowledgeUnitRelationMapper {
    /** 建立版本到来源记录的关联；已存在时不重复插入。 */
    int insertIgnoreSourceLink(@Param("versionId") long versionId, @Param("sourceRecordId") long sourceRecordId,
            @Param("relationType") String relationType);
}
