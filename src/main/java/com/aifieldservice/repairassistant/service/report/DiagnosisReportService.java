package com.aifieldservice.repairassistant.service.report;

import java.util.List;
import com.aifieldservice.repairassistant.domain.report.command.SaveReportRequest;
import com.aifieldservice.repairassistant.domain.report.model.SavedReport;


/** 固化并读取不可变的诊断报告快照。 */
public interface DiagnosisReportService {

    /** 从诊断会话生成报告；同一会话重复保存时返回既有报告。 */
    SavedReport saveReport(String sessionId, SaveReportRequest request);

    /** 按创建时间读取全部已保存报告的摘要。 */
    List<SavedReport> listReports();

    /** 读取指定报告及其当时的完整诊断快照。 */
    SavedReport getReport(String reportId);
}
