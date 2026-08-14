package com.aifieldservice.repairassistant.controller.diagnosis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteRediagnosisRequest;
import com.aifieldservice.repairassistant.domain.onsite.command.RejectionRequest;
import com.aifieldservice.repairassistant.service.diagnosis.ProblemUnderstandingService;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteReanalysisService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteQuestionService;
import com.aifieldservice.repairassistant.service.onsite.OnsiteSessionService;
import com.aifieldservice.repairassistant.service.report.DiagnosisReportService;

class DiagnosisControllerOnsiteReanalysisTests {

    private DiagnosisService diagnosisService;
    private OnsiteReanalysisService onsiteReanalysisService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        diagnosisService = mock(DiagnosisService.class);
        onsiteReanalysisService = mock(OnsiteReanalysisService.class);
        OnsiteQuestionService onsiteQuestionService = mock(OnsiteQuestionService.class);
        OnsiteSessionService onsiteSessionService = mock(OnsiteSessionService.class);
        ProblemUnderstandingService understandingService = mock(ProblemUnderstandingService.class);
        DiagnosisReportService reportService = mock(DiagnosisReportService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DiagnosisController(
                        understandingService, diagnosisService, onsiteReanalysisService,
                        onsiteQuestionService, onsiteSessionService, reportService)).build();
    }

    @Test
    void preparesThenStartsOnsiteRediagnosisWithConfirmedUnderstanding() throws Exception {
        RejectionRequest rejection = new RejectionRequest(
                "加热器阻值正常，蒸发器风机未运转。");
        ProblemUnderstanding understanding = understanding("understanding-2");
        DiagnosisSession replacement = replacementSession(understanding);
        when(onsiteReanalysisService.prepare("onsite-1", rejection))
                .thenReturn(understanding);
        when(onsiteReanalysisService.start(
                "onsite-1", new OnsiteRediagnosisRequest(understanding.id(), rejection)))
                .thenReturn(replacement);

        mockMvc.perform(post("/api/v1/diagnosis-sessions/onsite-1/rejections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "onsiteObservation": "加热器阻值正常，蒸发器风机未运转。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("understanding-2"));

        mockMvc.perform(post("/api/v1/diagnosis-sessions/onsite-1/reanalysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "problemUnderstandingId": "understanding-2",
                                  "rejection": {
                                    "onsiteObservation": "加热器阻值正常，蒸发器风机未运转。"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("onsite-2"))
                .andExpect(jsonPath("$.stage").value("ONSITE"));

        verify(onsiteReanalysisService).prepare("onsite-1", rejection);
        verify(onsiteReanalysisService).start(
                "onsite-1", new OnsiteRediagnosisRequest(understanding.id(), rejection));
    }

    private ProblemUnderstanding understanding(String id) {
        return new ProblemUnderstanding(
                id,
                "RIR1-SSB 蒸发器风机未运转",
                "zh-CN",
                "现场发现指向风机回路。",
                new ProblemType("FAN_FAILURE", "蒸发器风机故障", 0.92),
                List.of(),
                true,
                null);
    }

    private DiagnosisSession replacementSession(ProblemUnderstanding understanding) {
        return new DiagnosisSession(
                "onsite-2",
                "ONSITE",
                "INSUFFICIENT_EVIDENCE",
                new AnalysisProgress("GENERATING_EXPLANATION", 100),
                understanding,
                List.of(),
                List.of(),
                null,
                null,
                Instant.parse("2026-08-05T00:00:00Z"));
    }
}
