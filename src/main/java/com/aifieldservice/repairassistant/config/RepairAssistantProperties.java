package com.aifieldservice.repairassistant.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务侧配置的统一入口。
 *
 * <p>这里刻意只描述“维修助手需要什么”，不包含具体客户端实现细节。
 * 因此后续替换 OpenAI 模型、Qdrant 集群或知识目录时，业务服务不需要直接读取环境变量。
 */
@ConfigurationProperties(prefix = "repair-assistant")
public record RepairAssistantProperties(
        Web web,
        Knowledge knowledge,
        Qdrant qdrant,
        OpenAi openai,
        ProblemUnderstanding problemUnderstanding) {

    /** 前端可访问 API 的来源白名单。 */
    public record Web(List<String> allowedOrigins) {
    }

    /** 固定知识包的目录、逻辑版本，以及启动时是否执行导入。 */
    public record Knowledge(String sourcePath, String version, boolean importEnabled) {
    }

    /** Qdrant REST 地址、鉴权信息和当前知识集合名称。 */
    public record Qdrant(String url, String apiKey, String collection) {
    }

    /**
     * OpenAI 调用参数。
     * embeddingDimensions 必须与 Qdrant collection 的向量维度保持一致。
     */
    public record OpenAi(
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            int embeddingDimensions) {
    }

    /** 问题理解中规则和语义兜底的可运营阈值。 */
    public record ProblemUnderstanding(
            boolean semanticFallbackEnabled,
            int ruleClassificationAcceptScore,
            int ruleClassificationMinScoreGap,
            double llmClassificationAcceptScore,
            boolean llmFieldCompletionAfterClassificationEnabled) {
    }
}
