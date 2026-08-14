package com.aifieldservice.repairassistant.dao.report;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.report.model.SavedDiagnosisReport;

/** Data access for user-saved immutable diagnosis reports. */
public interface SavedDiagnosisReportMapper {

    int insert(@Param("reportKey") String reportKey,
            @Param("sessionKey") String sessionKey,
            @Param("reportName") String reportName,
            @Param("note") String note,
            @Param("stage") String stage,
            @Param("diagnosisStatus") String diagnosisStatus,
            @Param("snapshotJson") String snapshotJson);

    /** 按默认排序读取全部已保存报告。 */
    List<SavedDiagnosisReport> findAll();

    /** 按报告业务标识读取持久化快照；不存在时返回 null。 */
    SavedDiagnosisReport findByReportKey(@Param("reportKey") String reportKey);

    /** 查询某诊断会话已保存的报告，供幂等保存使用。 */
    SavedDiagnosisReport findBySessionKey(@Param("sessionKey") String sessionKey);
}
