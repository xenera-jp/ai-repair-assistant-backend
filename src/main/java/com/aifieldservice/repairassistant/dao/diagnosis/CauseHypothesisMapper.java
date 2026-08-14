package com.aifieldservice.repairassistant.dao.diagnosis;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.diagnosis.model.CauseHypothesis;

/** Reads reviewed cause hypotheses and their onsite-question templates. */
public interface CauseHypothesisMapper {

    String findClarificationQuestionsJson(@Param("code") String code);

    List<CauseHypothesis> findByProblemTypeCode(@Param("problemTypeCode") String problemTypeCode);
}
