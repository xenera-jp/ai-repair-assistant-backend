package com.aifieldservice.repairassistant.controller.diagnosis;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.controller.diagnosis.dto.DiagnosisSession;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.OnsiteQuestionResponseRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.OnsiteRediagnosisRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.ProblemUnderstanding;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.RejectionRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.SaveReportRequest;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.SavedReport;
import com.aifieldservice.repairassistant.controller.diagnosis.dto.StartDiagnosisRequest;
import com.aifieldservice.repairassistant.service.diagnosis.ProblemUnderstandingService;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteReanalysisService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteQuestionService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteSessionService;
import com.aifieldservice.repairassistant.service.report.DiagnosisReportService;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 诊断应用的 HTTP 边界。
 *
 * <p>Controller 只负责请求校验、HTTP 状态码和用例转发，所有问题理解、
 * 检索、评分与现场追问规则都保留在 Service 中，防止前后端或接口层复制业务规则。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class DiagnosisController {

    private final ProblemUnderstandingService understandingService;
    private final DiagnosisService diagnosisService;
    private final OnsiteReanalysisService onsiteReanalysisService;
    private final OnsiteQuestionService onsiteQuestionService;
    private final OnsiteSessionService onsiteSessionService;
    private final DiagnosisReportService reportService;
    private final DiagnosisDtoMapper dtoMapper = new DiagnosisDtoMapper();

    public DiagnosisController(
            ProblemUnderstandingService understandingService,
            DiagnosisService diagnosisService,
            OnsiteReanalysisService onsiteReanalysisService,
            OnsiteQuestionService onsiteQuestionService,
            OnsiteSessionService onsiteSessionService,
            DiagnosisReportService reportService) {
        this.understandingService = understandingService;
        this.diagnosisService = diagnosisService;
        this.onsiteReanalysisService = onsiteReanalysisService;
        this.onsiteQuestionService = onsiteQuestionService;
        this.onsiteSessionService = onsiteSessionService;
        this.reportService = reportService;
    }

    /** 将自然语言整理成可检索、可校验的问题模型。 */
    @PostMapping("/problem-understandings")
    public ProblemUnderstanding understand(
            @Valid @RequestBody ProblemUnderstandingRequest request) {
        return dtoMapper.toDto(understandingService.understand(dtoMapper.toDomain(request)));
    }

    /** 基于已保存的问题理解结果发起出发前诊断。 */
    @PostMapping("/diagnosis-sessions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DiagnosisSession start(
            @Valid @RequestBody StartDiagnosisRequest request) {
        return dtoMapper.toDto(diagnosisService.start(dtoMapper.toDomain(request)));
    }

    /** 查询既有诊断会话的完整快照，供刷新页面或恢复流程使用。 */
    @GetMapping("/diagnosis-sessions/{sessionId}")
    public DiagnosisSession get(@PathVariable String sessionId) {
        return dtoMapper.toDto(diagnosisService.get(sessionId));
    }

    /** 从出发前诊断派生一个最多三轮的现场分析会话。 */
    @PostMapping("/diagnosis-sessions/{sessionId}/onsite")
    public DiagnosisSession enterOnsite(@PathVariable String sessionId) {
        return dtoMapper.toDto(onsiteSessionService.enter(sessionId));
    }

    /** 记录现场对原诊断的否定事实，并返回待确认的重新理解结果。 */
    @PostMapping("/diagnosis-sessions/{sessionId}/rejections")
    public ProblemUnderstanding reject(
            @PathVariable String sessionId,
            @RequestBody RejectionRequest request) {
        return dtoMapper.toDto(onsiteReanalysisService.prepare(sessionId, dtoMapper.toDomain(request)));
    }

    /** 确认新的问题理解后，从原会话派生一份现场重新诊断会话。 */
    @PostMapping("/diagnosis-sessions/{sessionId}/reanalysis")
    public DiagnosisSession startOnsiteRediagnosis(
            @PathVariable String sessionId,
            @RequestBody OnsiteRediagnosisRequest request) {
        return dtoMapper.toDto(onsiteReanalysisService.start(sessionId, dtoMapper.toDomain(request)));
    }

    /** 保存一条现场事实，并用它重新排序当前候选原因。 */
    @PostMapping(
            "/diagnosis-sessions/{sessionId}/questions/{questionId}/responses")
    public DiagnosisSession answerOnsiteQuestion(
            @PathVariable String sessionId,
            @PathVariable String questionId,
            @RequestBody OnsiteQuestionResponseRequest request) {
        return dtoMapper.toDto(onsiteQuestionService.answer(
                sessionId,
                questionId,
                dtoMapper.toDomain(request)));
    }

    /**
     * 用户明确点击保存时才固化报告；普通试问不会自动产生大量无效报告。
     */
    @PostMapping("/diagnosis-sessions/{sessionId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedReport saveReport(
            @PathVariable String sessionId,
            @RequestBody(required = false) SaveReportRequest request) {
        return dtoMapper.toDto(reportService.saveReport(
                sessionId,
                dtoMapper.toDomain(request == null ? new SaveReportRequest(null, null) : request)));
    }

    /** 查询全部已保存报告的摘要列表，不产生新的报告快照。 */
    @GetMapping("/reports")
    public List<SavedReport> listReports() {
        return reportService.listReports().stream().map(dtoMapper::toDto).toList();
    }

    /** 按报告标识读取保存时固化的完整诊断结果。 */
    @GetMapping("/reports/{reportId}")
    public SavedReport getReport(@PathVariable String reportId) {
        return dtoMapper.toDto(reportService.getReport(reportId));
    }
}
