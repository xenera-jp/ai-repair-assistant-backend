package com.aifieldservice.repairassistant.service.onsite;

import java.util.List;

import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteAnswer;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteSessionState;



/** 读写现场追问进度和已经归一化的答案。 */
public interface OnsiteSessionStateService {

    /** 获取指定会话的状态；不存在时按业务规则报错。 */
    OnsiteSessionState require(String sessionId);

    /** 持久化下一轮轮次编号与当前全部已回答的现场信号。 */
    void saveProgress(String sessionId, int nextRound, List<OnsiteAnswer> answers);
}
