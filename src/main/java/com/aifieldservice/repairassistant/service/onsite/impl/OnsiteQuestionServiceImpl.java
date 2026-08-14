package com.aifieldservice.repairassistant.service.onsite.impl;

import com.aifieldservice.repairassistant.service.onsite.*;
import com.aifieldservice.repairassistant.service.onsite.OnsiteQuestionService.*;

import org.springframework.stereotype.Service;

import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest;

/** Application entry point for a single onsite-question response. */
@Service
public class OnsiteQuestionServiceImpl implements OnsiteQuestionService {
    private final OnsiteAnswerService onsiteAnswerService;

    public OnsiteQuestionServiceImpl(OnsiteAnswerService onsiteAnswerService) {
        this.onsiteAnswerService = onsiteAnswerService;
    }

    public DiagnosisSession answer(String sessionId, String questionId,
            OnsiteQuestionResponseRequest request) {
        return onsiteAnswerService.answer(sessionId, questionId, request);
    }
}
