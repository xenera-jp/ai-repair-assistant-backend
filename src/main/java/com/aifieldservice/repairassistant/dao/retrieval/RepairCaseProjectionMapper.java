package com.aifieldservice.repairassistant.dao.retrieval;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.aifieldservice.repairassistant.domain.retrieval.model.RepairCaseProjection;

/** Read-only SQL-first retrieval over resolved repair-case projections. */
public interface RepairCaseProjectionMapper {

    List<RepairCaseProjection> findResolvedCases(@Param("model") String model,
            @Param("problemTypeCode") String problemTypeCode);

    List<RepairCaseProjection> findByReceptionIds(@Param("receptionIds") List<String> receptionIds);
}
