package com.aifieldservice.repairassistant.service.onsite.impl;

import com.aifieldservice.repairassistant.service.onsite.*;
import com.aifieldservice.repairassistant.service.onsite.OnsiteReanalysisService.*;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.domain.diagnosis.command.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
import com.aifieldservice.repairassistant.domain.diagnosis.model.ProblemUnderstanding;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteRediagnosisRequest;
import com.aifieldservice.repairassistant.domain.onsite.command.RejectionRequest;
import com.aifieldservice.repairassistant.dao.diagnosis.DiagnosisSnapshotMapper;
import com.aifieldservice.repairassistant.dao.onsite.OnsiteRejectionMapper;
import com.aifieldservice.repairassistant.dao.onsite.OnsiteSessionStateMapper;
import com.aifieldservice.repairassistant.service.diagnosis.DiagnosisService;
import com.aifieldservice.repairassistant.service.diagnosis.ProblemUnderstandingService;

import tools.jackson.databind.ObjectMapper;

/** Owns onsite rejection state and the creation of a replacement diagnosis session. */
@Service
public class OnsiteReanalysisServiceImpl implements OnsiteReanalysisService {

    private final DiagnosisService diagnosisService;
    private final ProblemUnderstandingService problemUnderstandingService;
    private final DiagnosisSnapshotMapper diagnosisSnapshotMapper;
    private final OnsiteRejectionMapper onsiteRejectionMapper;
    private final OnsiteSessionStateMapper onsiteSessionStateMapper;
    private final ObjectMapper objectMapper;

    public OnsiteReanalysisServiceImpl(
            DiagnosisService diagnosisService,
            ProblemUnderstandingService problemUnderstandingService,
            DiagnosisSnapshotMapper diagnosisSnapshotMapper,
            OnsiteRejectionMapper onsiteRejectionMapper,
            OnsiteSessionStateMapper onsiteSessionStateMapper,
            ObjectMapper objectMapper) {
        this.diagnosisService = diagnosisService;
        this.problemUnderstandingService = problemUnderstandingService;
        this.diagnosisSnapshotMapper = diagnosisSnapshotMapper;
        this.onsiteRejectionMapper = onsiteRejectionMapper;
        this.onsiteSessionStateMapper = onsiteSessionStateMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProblemUnderstanding prepare(String sessionId, RejectionRequest request) {
        DiagnosisSession rejected = requireActiveOnsiteSession(sessionId);
        validateRejection(request);
        String input = buildRediagnosisInput(request);
        return problemUnderstandingService.understand(new ProblemUnderstandingRequest(
                "ONSITE", rejected.problemUnderstanding().language(), input, sessionId));
    }

    @Transactional
    public DiagnosisSession start(String sessionId, OnsiteRediagnosisRequest request) {
        DiagnosisSession rejected = requireActiveOnsiteSession(sessionId);
        RejectionRequest rejection = request.rejection();
        validateRejection(rejection);
        if (onsiteRejectionMapper.countByOnsiteSessionKey(sessionId) > 0) {
            throw alreadyRejected();
        }

        ProblemUnderstanding understanding = problemUnderstandingService.get(
                request.problemUnderstandingId());
        DiagnosisSession rediagnosed = diagnosisService.startOnsiteDiagnosis(
                understanding, sessionId);
        onsiteSessionStateMapper.insert(rediagnosed.id(), sessionId, 1, 3, "[]");
        DiagnosisSession terminal = new DiagnosisSession(
                rejected.id(), rejected.stage(), "REJECTED", rejected.progress(),
                rejected.problemUnderstanding(), rejected.candidates(), rejected.evidenceGroups(),
                rejected.recommendations(), null, Instant.now());
        int changed = diagnosisSnapshotMapper.rejectIfActive(
                sessionId, terminal.status(), objectMapper.writeValueAsString(terminal));
        if (changed != 1) {
            throw alreadyRejected();
        }
        onsiteRejectionMapper.insert(sessionId, sessionId,
                strip(rejection.onsiteObservation()), rediagnosed.id());
        return rediagnosed;
    }

    private DiagnosisSession requireActiveOnsiteSession(String sessionId) {
        DiagnosisSession session = diagnosisService.get(sessionId);
        if (!"ONSITE".equals(session.stage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only onsite diagnosis sessions can be rejected.");
        }
        if ("REJECTED".equals(session.status())) {
            throw alreadyRejected();
        }
        return session;
    }

    private void validateRejection(RejectionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rejection request is required.");
        }
        String observation = strip(request.onsiteObservation());
        if (observation.isBlank() || observation.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "onsiteObservation is required and must not exceed 4000 characters.");
        }
    }

    private String buildRediagnosisInput(RejectionRequest request) {
        return strip(request.onsiteObservation());
    }

    private ResponseStatusException alreadyRejected() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "This onsite diagnosis has already been rejected.");
    }

    private String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
