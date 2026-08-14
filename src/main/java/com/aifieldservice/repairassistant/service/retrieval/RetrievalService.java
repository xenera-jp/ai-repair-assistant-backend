package com.aifieldservice.repairassistant.service.retrieval;

import java.util.List;

import com.aifieldservice.repairassistant.domain.retrieval.model.ManualKnowledgeProjection;
import com.aifieldservice.repairassistant.domain.retrieval.model.RepairCaseProjection;


/** 从已发布的关系型检索投影中获取案例和手册证据。 */
public interface RetrievalService {

    /** 按机型和故障类别获取已解决的历史维修案例。 */
    List<RepairCaseProjection> findResolvedCases(String model, String problemTypeCode);

    /** 按向量检索召回的接待单号补全维修案例。 */
    List<RepairCaseProjection> findCasesByReceptionIds(List<String> receptionIds);

    /** 按机型、故障类别和可选错误码进行结构化手册检索。 */
    List<ManualKnowledgeProjection> findStructuredManuals(
            String model, String problemTypeCode, String errorCode);

    /** 按手册知识主键补全向量检索召回的知识单元。 */
    List<ManualKnowledgeProjection> findManualsByIds(List<Long> ids);
}
