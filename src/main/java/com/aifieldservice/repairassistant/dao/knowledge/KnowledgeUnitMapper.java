package com.aifieldservice.repairassistant.dao.knowledge;
import org.apache.ibatis.annotations.Param;
/** 读写知识库内可版本化知识单元的 MyBatis Mapper。 */
public interface KnowledgeUnitMapper {
    /** 按知识库和业务键创建或更新知识单元，返回受影响行数。 */
    int upsert(@Param("knowledgeBaseId") long knowledgeBaseId, @Param("unitKey") String unitKey,
            @Param("unitType") String unitType);
    /** 按知识库和业务键查询知识单元主键。 */
    Long findId(@Param("knowledgeBaseId") long knowledgeBaseId, @Param("unitKey") String unitKey);
}
