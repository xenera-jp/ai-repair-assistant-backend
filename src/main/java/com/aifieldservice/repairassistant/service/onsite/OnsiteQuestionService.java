package com.aifieldservice.repairassistant.service.onsite;


import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest;


/** 面向接口层的现场问题应答服务。 */
public interface OnsiteQuestionService {

    /** 提交指定问题的回答，并返回重新评分后的诊断会话。 */
    DiagnosisSession answer(String sessionId, String questionId,
            OnsiteQuestionResponseRequest request);
}
