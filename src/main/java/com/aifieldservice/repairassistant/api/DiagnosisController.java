package com.aifieldservice.repairassistant.api;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.api.DiagnosisApiModels.DiagnosisSession;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.OnsiteQuestionResponseRequest;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstanding;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.SaveReportRequest;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.SavedReport;
import com.aifieldservice.repairassistant.api.DiagnosisApiModels.StartDiagnosisRequest;
import com.aifieldservice.repairassistant.diagnosis.ProblemUnderstandingService;
import com.aifieldservice.repairassistant.retrieval.DiagnosisService;

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

    public DiagnosisController(
            ProblemUnderstandingService understandingService,
            DiagnosisService diagnosisService) {
        this.understandingService = understandingService;
        this.diagnosisService = diagnosisService;
    }

    /** 将自然语言整理成可检索、可校验的问题模型。 */
    @PostMapping("/problem-understandings")
    public ProblemUnderstanding understand(
            @Valid @RequestBody ProblemUnderstandingRequest request) {
        return understandingService.understand(request);
    }

    /** 基于已保存的问题理解结果发起出发前诊断。 */
    @PostMapping("/diagnosis-sessions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DiagnosisSession start(
            @Valid @RequestBody StartDiagnosisRequest request) {
        return diagnosisService.start(request);
    }

    @GetMapping("/diagnosis-sessions/{sessionId}")
    public DiagnosisSession get(@PathVariable String sessionId) {
        return diagnosisService.get(sessionId);
    }

    /** 从出发前诊断派生一个最多三轮的现场分析会话。 */
    @PostMapping("/diagnosis-sessions/{sessionId}/onsite")
    public DiagnosisSession enterOnsite(@PathVariable String sessionId) {
        return diagnosisService.enterOnsite(sessionId);
    }

    /** 保存一条现场事实，并用它重新排序当前候选原因。 */
    @PostMapping(
            "/diagnosis-sessions/{sessionId}/questions/{questionId}/responses")
    public DiagnosisSession answerOnsiteQuestion(
            @PathVariable String sessionId,
            @PathVariable String questionId,
            @RequestBody OnsiteQuestionResponseRequest request) {
        return diagnosisService.answerOnsiteQuestion(
                sessionId,
                questionId,
                request);
    }

    /**
     * 用户明确点击保存时才固化报告；普通试问不会自动产生大量无效报告。
     */
    @PostMapping("/diagnosis-sessions/{sessionId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedReport saveReport(
            @PathVariable String sessionId,
            @RequestBody(required = false) SaveReportRequest request) {
        return diagnosisService.saveReport(
                sessionId,
                request == null ? new SaveReportRequest(null, null) : request);
    }

    @GetMapping("/reports")
    public List<SavedReport> listReports() {
        return diagnosisService.listReports();
    }

    @GetMapping("/reports/{reportId}")
    public SavedReport getReport(@PathVariable String reportId) {
        return diagnosisService.getReport(reportId);
    }
}
