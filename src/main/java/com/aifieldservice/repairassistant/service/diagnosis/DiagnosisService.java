package com.aifieldservice.repairassistant.service.diagnosis;

import java.util.List;
import java.util.Set;

import com.aifieldservice.repairassistant.domain.diagnosis.command.StartDiagnosisRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.*;


/** 编排出发前和现场诊断会话的应用服务。 */
public interface DiagnosisService {

    /** 根据已完成的问题理解创建并持久化一份出发前诊断快照。 */
    DiagnosisSession start(StartDiagnosisRequest request);

    /** 使用现场重新理解结果创建派生诊断会话。 */
    DiagnosisSession startOnsiteDiagnosis(
            ProblemUnderstanding understanding,
            String parentSessionKey);

    /** 基于候选原因及已回答信号，生成当前轮次应展示的第一道现场问题。 */
    OnsiteQuestion createInitialOnsiteQuestion(
            List<DiagnosisCandidate> candidates,
            Set<String> answeredFields,
            int round,
            ProblemUnderstanding understanding);

    /** 读取指定诊断会话的已持久化快照。 */
    DiagnosisSession get(String sessionId);
}
