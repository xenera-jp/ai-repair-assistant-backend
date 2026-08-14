package com.aifieldservice.repairassistant.service.onsite;


import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest;


/** 处理现场问题的回答并更新候选原因排序。 */
public interface OnsiteAnswerService {

    /** 校验问题归属、归一化答案、保存会话状态并返回更新后的诊断会话。 */
    DiagnosisSession answer(String sessionId, String questionId, OnsiteQuestionResponseRequest request);
}
