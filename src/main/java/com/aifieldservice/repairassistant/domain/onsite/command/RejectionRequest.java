package com.aifieldservice.repairassistant.domain.onsite.command;
/**
 * 记录技术人员发现原诊断不成立时的现场观察。
 * @param onsiteObservation 技术人员记录的否定事实或补充现象
 */
public record RejectionRequest(String onsiteObservation) {}
