package com.aifieldservice.repairassistant.dao.knowledge;

import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.knowledge.model.ManualDocumentRecord;

/** Resolves a manual projection id to its registered source file. */
public interface ManualDocumentMapper {

    ManualDocumentRecord findRegisteredDocument(@Param("manualKnowledgeId") long manualKnowledgeId);
}
