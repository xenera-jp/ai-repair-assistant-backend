package com.aifieldservice.repairassistant.service.onsite;


import com.aifieldservice.repairassistant.domain.diagnosis.model.*;


/** 管理从出发前诊断切换到现场诊断的会话生命周期。 */
public interface OnsiteSessionService {

    /** 由出发前会话派生现场会话，并初始化有限轮次的追问状态。 */
    DiagnosisSession enter(String parentSessionId);
}
