package com.aifieldservice.repairassistant.service.onsite.impl;

import com.aifieldservice.repairassistant.service.onsite.*;
import com.aifieldservice.repairassistant.service.onsite.OnsiteSessionStateService.*;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.dao.onsite.OnsiteSessionStateMapper;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteSessionStateRecord;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteAnswer;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteSessionState;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Owns persistence and recovery of onsite session-state snapshots. */
@Service
public class OnsiteSessionStateServiceImpl implements OnsiteSessionStateService {
    private static final TypeReference<List<OnsiteAnswer>> ANSWERS_TYPE = new TypeReference<>() { };
    private final OnsiteSessionStateMapper mapper;
    private final ObjectMapper objectMapper;

    public OnsiteSessionStateServiceImpl(OnsiteSessionStateMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OnsiteSessionState require(String sessionId) {
        OnsiteSessionStateRecord row = mapper.findBySessionKey(sessionId);
        if (row == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "现场会话状态不存在，请重新进入现场分析。");
        try {
            return new OnsiteSessionState(row.currentRound(), row.maxRounds(),
                    objectMapper.readValue(row.answeredSignalsJson(), ANSWERS_TYPE));
        } catch (Exception ignored) {
            return new OnsiteSessionState(row.currentRound(), row.maxRounds(), List.of());
        }
    }

    public void saveProgress(String sessionId, int nextRound, List<OnsiteAnswer> answers) {
        mapper.updateProgress(sessionId, nextRound, objectMapper.writeValueAsString(answers));
    }
}
