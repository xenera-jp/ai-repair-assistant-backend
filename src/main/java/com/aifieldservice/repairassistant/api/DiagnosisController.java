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

    @PostMapping("/problem-understandings")
    public ProblemUnderstanding understand(
            @Valid @RequestBody ProblemUnderstandingRequest request) {
        return understandingService.understand(request);
    }

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

    @PostMapping("/diagnosis-sessions/{sessionId}/onsite")
    public DiagnosisSession enterOnsite(@PathVariable String sessionId) {
        return diagnosisService.enterOnsite(sessionId);
    }

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
