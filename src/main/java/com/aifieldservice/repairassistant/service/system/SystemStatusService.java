package com.aifieldservice.repairassistant.service.system;

import java.time.Instant;



/** 生成与 HTTP 无关的系统健康状态读取模型。 */
public interface SystemStatusService {

    /** 返回服务可用性、知识库版本和外部集成配置状态。 */
    SystemStatus status();

    public record SystemStatus(
            String service,
            String status,
            String knowledgeVersion,
            IntegrationStatus integrations,
            Instant timestamp,
            String sayHi) {
    }

    public record IntegrationStatus(boolean qdrantConfigured, boolean openAiConfigured) {
    }
}
