package com.aifieldservice.repairassistant.service.knowledge;

import java.util.List;
import java.util.Optional;


/** 查询和匹配故障类别目录。 */
public interface ProblemCatalogService {

    /** 返回所有可用故障类别及其匹配规则。 */
    List<ProblemTypeDefinition> all();

    /** 结合机型、错误码和原始描述寻找得分最高的故障类别。 */
    Optional<ProblemMatch> match(
            String model,
            String errorCode,
            String text);

    /**
     * 返回全部有足够规则信号的候选，并按支持分从高到低排序。
     * 问题理解需要比较第一、第二候选的分差，不能只看 Top-1 分数。
     */
    List<ProblemMatch> matchCandidates(String model, String errorCode, String text);

    /** 按稳定业务编码查询故障类别。 */
    Optional<ProblemTypeDefinition> findByCode(String code);

    public record ProblemTypeDefinition(
            long id,
            String code,
            String nameZh,
            String nameJa,
            List<String> sourceLabels,
            List<String> aliases,
            List<String> modelScopes,
            List<ErrorCodeDefinition> errorCodes,
            List<ClarificationDefinition> clarifications) {
    }

    public record ErrorCodeDefinition(String code, List<String> models) {
    }

    public record ClarificationDefinition(String field, String level, String questionZh) {
    }

    public record ProblemMatch(
            ProblemTypeDefinition definition,
            int score,
            List<String> matchedSignals) {
    }
}
