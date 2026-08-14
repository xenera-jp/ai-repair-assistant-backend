package com.aifieldservice.repairassistant.dao.onsite;

import org.apache.ibatis.annotations.Param;

/** Stores the one-time rejection link for an onsite diagnosis. */
public interface OnsiteRejectionMapper {

    int countByOnsiteSessionKey(@Param("onsiteSessionKey") String onsiteSessionKey);

    int insert(@Param("onsiteSessionKey") String onsiteSessionKey,
            @Param("rejectedSessionKey") String rejectedSessionKey,
            @Param("onsiteObservation") String onsiteObservation,
            @Param("rediagnosedSessionKey") String rediagnosedSessionKey);
}
