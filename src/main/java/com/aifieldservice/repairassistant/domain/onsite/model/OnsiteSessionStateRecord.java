package com.aifieldservice.repairassistant.domain.onsite.model;

/**
 * 现场会话状态的持久化形态，其中答案以 JSON 字符串保存。
 * @param currentRound 当前或下一轮追问编号
 * @param maxRounds 允许进行的最大追问轮次
 * @param answeredSignalsJson 已回答信号的 JSON 序列化内容
 */
public record OnsiteSessionStateRecord(int currentRound, int maxRounds, String answeredSignalsJson) {
}
