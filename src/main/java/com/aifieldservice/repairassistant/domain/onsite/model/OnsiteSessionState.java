package com.aifieldservice.repairassistant.domain.onsite.model;

import java.util.List;

/**
 * 单次现场诊断的有限轮次追问状态。
 * @param currentRound 当前或下一轮追问编号
 * @param maxRounds 允许进行的最大追问轮次
 * @param answers 已归一化并保存的全部回答
 */
public record OnsiteSessionState(int currentRound, int maxRounds, List<OnsiteAnswer> answers) {
}
