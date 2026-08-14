package com.aifieldservice.repairassistant.service.onsite.impl;

import com.aifieldservice.repairassistant.service.onsite.*;
import com.aifieldservice.repairassistant.service.onsite.OnsiteSessionService.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.dao.diagnosis.DiagnosisSnapshotMapper;
import com.aifieldservice.repairassistant.dao.onsite.OnsiteSessionStateMapper;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService;

import tools.jackson.databind.ObjectMapper;

/** Creates or reuses the bounded onsite session for a diagnosis. */
@Service
public class OnsiteSessionServiceImpl implements OnsiteSessionService {
    private final DiagnosisService diagnosisService;
    private final OnsiteSessionStateMapper stateMapper;
    private final DiagnosisSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final OnsiteQuestionFactory questionFactory;

    public OnsiteSessionServiceImpl(DiagnosisService diagnosisService,
            OnsiteSessionStateMapper stateMapper,
            DiagnosisSnapshotMapper snapshotMapper,
            ObjectMapper objectMapper,
            OnsiteQuestionFactory questionFactory) {
        this.diagnosisService = diagnosisService;
        this.stateMapper = stateMapper;
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
        this.questionFactory = questionFactory;
    }

    @Transactional
    public DiagnosisSession enter(String parentSessionId) {
        DiagnosisSession parent = diagnosisService.get(parentSessionId);
        if ("ONSITE".equals(parent.stage())) {
            return parent;
        }
        String existing = stateMapper.findLatestSessionKeyByParent(parentSessionId);
        if (existing != null) {
            return diagnosisService.get(existing);
        }
        OnsiteQuestion question = parent.candidates().isEmpty() ? null
                : questionFactory.create(parent.candidates(), Set.of(), 1,
                        "ja-JP".equals(parent.problemUnderstanding().language()));
        String status = parent.candidates().isEmpty() ? "INSUFFICIENT_EVIDENCE"
                : question == null ? "PARTIALLY_SUPPORTED" : "ONSITE_QUESTIONING";
        DiagnosisSession onsite = new DiagnosisSession(
                UUID.randomUUID().toString(), "ONSITE", status,
                new AnalysisProgress("ONSITE_QUESTION_GENERATION", 100),
                parent.problemUnderstanding(), parent.candidates(), parent.evidenceGroups(),
                parent.recommendations(), question, Instant.now());
        snapshotMapper.insert(onsite.id(), onsite.problemUnderstanding().id(), onsite.stage(),
                onsite.status(), objectMapper.writeValueAsString(onsite));
        stateMapper.insert(onsite.id(), parentSessionId, 1, 3, "[]");
        return onsite;
    }
}
