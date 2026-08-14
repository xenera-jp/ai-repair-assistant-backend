package com.aifieldservice.repairassistant.dao.knowledge;

import java.util.List;
import com.aifieldservice.repairassistant.domain.knowledge.model.ProblemTypeRecord;

/** Reads the reviewed problem taxonomy used by rule-based classification. */
public interface ProblemCatalogMapper {

    List<ProblemTypeRecord> findAllActive();
}
