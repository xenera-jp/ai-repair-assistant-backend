package com.aifieldservice.repairassistant.dao.knowledge;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.knowledge.command.ManualProjectionCommand;
import com.aifieldservice.repairassistant.domain.knowledge.model.PendingManualVector;

/** MyBatis statements specific to publishing reviewed service-manual knowledge. */
public interface ManualKnowledgeImportMapper {
    int countPublished(@Param("knowledgeBaseCode") String knowledgeBaseCode, @Param("sha256") String sha256,
            @Param("parserVersion") String parserVersion);
    Long findProblemTypeId(@Param("code") String code);
    int upsertPage(@Param("sourceFileId") long sourceFileId, @Param("businessKey") String businessKey,
            @Param("pageIndex") int pageIndex, @Param("cellRange") String cellRange,
            @Param("rawPayload") String rawPayload, @Param("fingerprint") String fingerprint);
    /** 按源文件与页面业务键查询已登记的手册页面主键。 */
    Long findPageId(@Param("sourceFileId") long sourceFileId, @Param("businessKey") String businessKey);
    /** 写入或更新知识单元的可发布版本与向量点标识。 */
    int upsertVersion(@Param("unitId") long unitId, @Param("title") String title,
            @Param("contentJson") String contentJson, @Param("sourceFingerprint") String sourceFingerprint,
            @Param("contentFingerprint") String contentFingerprint, @Param("pointId") String pointId);
    /** 查询知识单元当前版本的主键。 */
    Long findVersionId(@Param("unitId") long unitId);
    /** 将手册知识版本关联到故障类别。 */
    int linkProblemType(@Param("versionId") long versionId, @Param("problemTypeId") long problemTypeId);
    /** 建立知识版本与源记录的可追溯关联。 */
    int upsertSourceLink(@Param("versionId") long versionId, @Param("sourceRecordId") long sourceRecordId);
    /** 写入或更新用于结构化检索的文本投影。 */
    int upsertSearchProjection(@Param("versionId") long versionId, @Param("type") String type,
            @Param("text") String text, @Param("hash") String hash);
    /** 将解析后的手册单元发布到手册检索投影。 */
    int upsertManualProjection(ManualProjectionCommand command);
    /** 查询等待写入向量库的手册知识投影。 */
    List<PendingManualVector> findPendingVectors();
    /** 将指定投影标记为已完成向量索引。 */
    int markIndexed(@Param("ids") List<Long> ids);
}
