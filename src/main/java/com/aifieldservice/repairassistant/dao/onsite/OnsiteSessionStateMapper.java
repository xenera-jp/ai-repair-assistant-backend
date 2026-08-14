package com.aifieldservice.repairassistant.dao.onsite;

import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteSessionStateRecord;

/** Persistence boundary for a derived onsite diagnosis session. */
public interface OnsiteSessionStateMapper {

    int insert(@Param("sessionKey") String sessionKey,
            @Param("parentSessionKey") String parentSessionKey,
            @Param("currentRound") int currentRound,
            @Param("maxRounds") int maxRounds,
            @Param("answeredSignalsJson") String answeredSignalsJson);

    /** 查询某出发前会话最近派生出的现场会话标识。 */
    String findLatestSessionKeyByParent(@Param("parentSessionKey") String parentSessionKey);

    /** 按现场会话标识读取轮次及已回答信号状态。 */
    OnsiteSessionStateRecord findBySessionKey(@Param("sessionKey") String sessionKey);

    /** 更新现场追问轮次和序列化的已回答信号。 */
    int updateProgress(@Param("sessionKey") String sessionKey,
            @Param("currentRound") int currentRound,
            @Param("answeredSignalsJson") String answeredSignalsJson);

}
