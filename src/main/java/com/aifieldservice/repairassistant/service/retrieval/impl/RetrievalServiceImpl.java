package com.aifieldservice.repairassistant.service.retrieval.impl;

import com.aifieldservice.repairassistant.service.retrieval.*;
import com.aifieldservice.repairassistant.service.retrieval.RetrievalService.*;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifieldservice.repairassistant.dao.retrieval.ManualKnowledgeProjectionMapper;
import com.aifieldservice.repairassistant.dao.retrieval.RepairCaseProjectionMapper;
import com.aifieldservice.repairassistant.domain.retrieval.model.ManualKnowledgeProjection;
import com.aifieldservice.repairassistant.domain.retrieval.model.RepairCaseProjection;

/** Owns SQL-backed retrieval of published repair cases and service manuals. */
@Service
public class RetrievalServiceImpl implements RetrievalService {

    private final RepairCaseProjectionMapper repairCaseMapper;
    private final ManualKnowledgeProjectionMapper manualKnowledgeMapper;

    public RetrievalServiceImpl(
            RepairCaseProjectionMapper repairCaseMapper,
            ManualKnowledgeProjectionMapper manualKnowledgeMapper) {
        this.repairCaseMapper = repairCaseMapper;
        this.manualKnowledgeMapper = manualKnowledgeMapper;
    }

    @Transactional(readOnly = true)
    public List<RepairCaseProjection> findResolvedCases(String model, String problemTypeCode) {
        return repairCaseMapper.findResolvedCases(model, problemTypeCode);
    }

    @Transactional(readOnly = true)
    public List<RepairCaseProjection> findCasesByReceptionIds(List<String> receptionIds) {
        return receptionIds.isEmpty() ? List.of() : repairCaseMapper.findByReceptionIds(receptionIds);
    }

    @Transactional(readOnly = true)
    public List<ManualKnowledgeProjection> findStructuredManuals(
            String model, String problemTypeCode, String errorCode) {
        return manualKnowledgeMapper.findStructuredManuals(model, problemTypeCode, errorCode);
    }

    @Transactional(readOnly = true)
    public List<ManualKnowledgeProjection> findManualsByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : manualKnowledgeMapper.findByIds(ids);
    }
}
