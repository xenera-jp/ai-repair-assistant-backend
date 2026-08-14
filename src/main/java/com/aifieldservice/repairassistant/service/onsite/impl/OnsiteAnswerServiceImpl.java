package com.aifieldservice.repairassistant.service.onsite.impl;

import com.aifieldservice.repairassistant.service.onsite.*;
import com.aifieldservice.repairassistant.service.onsite.OnsiteAnswerService.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest;
import com.aifieldservice.repairassistant.dao.diagnosis.DiagnosisSnapshotMapper;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteAnswer;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteSessionState;

import tools.jackson.databind.ObjectMapper;

/** Application service for one optimistic-concurrency protected onsite answer. */
@Service
public class OnsiteAnswerServiceImpl implements OnsiteAnswerService {
    private final DiagnosisSnapshotMapper snapshotMapper;
    private final OnsiteSessionStateService stateService;
    private final OnsiteAnswerNormalizer normalizer;
    private final OnsiteCandidateScoringPolicy scoringPolicy;
    private final OnsiteQuestionFactory questionFactory;
    private final ObjectMapper objectMapper;

    public OnsiteAnswerServiceImpl(DiagnosisSnapshotMapper snapshotMapper,
            OnsiteSessionStateService stateService, OnsiteAnswerNormalizer normalizer,
            OnsiteCandidateScoringPolicy scoringPolicy, OnsiteQuestionFactory questionFactory,
            ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.stateService = stateService;
        this.normalizer = normalizer;
        this.scoringPolicy = scoringPolicy;
        this.questionFactory = questionFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DiagnosisSession answer(String sessionId, String questionId, OnsiteQuestionResponseRequest request) {
        DiagnosisSession session = requireSession(sessionId);
        if (!"ONSITE".equals(session.stage()) || session.nextQuestion() == null) {
            throw conflict("当前现场会话没有待回答问题。");
        }
        if (!session.nextQuestion().id().equals(questionId)) {
            throw conflict("现场问题已更新，请刷新后重新回答。");
        }
        OnsiteSessionState state = stateService.require(sessionId);
        boolean japanese = "ja-JP".equals(session.problemUnderstanding().language());
        OnsiteAnswer answer = normalizer.normalize(session.nextQuestion(), request, japanese);
        List<OnsiteAnswer> answers = new ArrayList<>(state.answers());
        answers.add(answer);
        List<DiagnosisCandidate> candidates = scoringPolicy.rescore(
                session.candidates(), session.nextQuestion(), answer, japanese);
        List<EvidenceGroup> evidence = scoringPolicy.appendEvidence(
                session.evidenceGroups(), session.nextQuestion(), answer, japanese);
        int currentRound = state.currentRound();
        OnsiteQuestion next = null;
        String status;
        if (scoringPolicy.isConverged(candidates)) status = "CONVERGED";
        else if (currentRound >= state.maxRounds()) status = scoringPolicy.hasSupportedCandidate(candidates)
                ? "PARTIALLY_SUPPORTED" : "INSUFFICIENT_EVIDENCE";
        else {
            Set<String> fields = answers.stream().map(OnsiteAnswer::field)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            next = questionFactory.create(candidates, fields, currentRound + 1, japanese);
            status = next == null ? (scoringPolicy.hasSupportedCandidate(candidates)
                    ? "PARTIALLY_SUPPORTED" : "INSUFFICIENT_EVIDENCE") : "ONSITE_QUESTIONING";
        }
        DiagnosisSession updated = new DiagnosisSession(session.id(), "ONSITE", status,
                new AnalysisProgress("ONSITE_REANALYSIS", 100), session.problemUnderstanding(),
                candidates, evidence, session.recommendations(), next, Instant.now());
        snapshotMapper.update(updated.id(), updated.stage(), updated.status(), objectMapper.writeValueAsString(updated));
        stateService.saveProgress(sessionId, next == null ? currentRound : next.round(), answers);
        return updated;
    }

    private DiagnosisSession requireSession(String id) {
        String payload = snapshotMapper.findPayloadBySessionKey(id);
        if (payload == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断会话不存在。");
        return objectMapper.readValue(payload, DiagnosisSession.class);
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
