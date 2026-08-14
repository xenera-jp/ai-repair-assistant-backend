package com.aifieldservice.repairassistant.dao.knowledge;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.knowledge.command.RepairCaseProjectionCommand;
import com.aifieldservice.repairassistant.domain.knowledge.model.PendingCaseVector;
/** 导入、发布维修案例并维护其向量索引状态的 MyBatis Mapper。 */
public interface RepairCaseImportMapper {
    /** 统计当前已登记的维修案例记录数。 */
    int countAll();
    /** 为知识单元插入版本；同一来源版本已存在时忽略。 */
    int insertIgnoreVersion(@Param("unitId") long unitId, @Param("title") String title,
            @Param("trustLevel") String trustLevel, @Param("contentJson") String contentJson,
            @Param("sourceFingerprint") String sourceFingerprint, @Param("contentFingerprint") String contentFingerprint,
            @Param("pointId") String pointId);
    /** 查询知识单元最新版本主键。 */
    Long findVersionId(@Param("unitId") long unitId);
    /** 将版本关联到故障类别。 */
    int linkProblemType(@Param("versionId") long versionId, @Param("problemTypeId") long problemTypeId);
    /** 新增用于关系型检索的文本投影。 */
    int insertSearchProjection(@Param("versionId") long versionId, @Param("type") String type,
            @Param("text") String text, @Param("hash") String hash);
    /** 写入或更新案例的诊断检索投影。 */
    int upsertProjection(RepairCaseProjectionCommand command);
    /** 查询尚未写入向量库的案例投影。 */
    List<PendingCaseVector> findPendingVectors();
    /** 将指定投影标记为已完成向量索引。 */
    int markIndexed(@Param("ids") List<Long> ids);
}
