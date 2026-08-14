package com.aifieldservice.repairassistant.service.onsite;

import com.aifieldservice.repairassistant.domain.diagnosis.model.DiagnosisSession;
import com.aifieldservice.repairassistant.domain.diagnosis.model.ProblemUnderstanding;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteRediagnosisRequest;
import com.aifieldservice.repairassistant.domain.onsite.command.RejectionRequest;


/** 处理现场否定原结论后的问题重理解和重新诊断。 */
public interface OnsiteReanalysisService {

    /** 保存否定观察，并据此准备一份待确认的问题理解结果。 */
    ProblemUnderstanding prepare(String sessionId, RejectionRequest request);

    /** 确认重理解结果后，创建并启动派生的现场诊断会话。 */
    DiagnosisSession start(String sessionId, OnsiteRediagnosisRequest request);
}
