package com.aifieldservice.repairassistant.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;

/**
 * 给前端和部署检查使用的轻量状态接口。
 *
 * <p>这里返回的是“是否完成配置”，不是对 OpenAI/Qdrant 的实时连通性探测；
 * 实时健康检查应放在 Actuator HealthIndicator 中，避免页面请求触发外部调用。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final RepairAssistantProperties properties;

    public SystemStatusController(RepairAssistantProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse(
                "ai-repair-assistant-backend",
                "UP",
                properties.knowledge().version(),
                new IntegrationStatus(
                        isConfigured(properties.qdrant().url()),
                        isConfigured(properties.openai().apiKey())),
                Instant.now());
    }

    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    public record SystemStatusResponse(
            String service,
            String status,
            String knowledgeVersion,
            IntegrationStatus integrations,
            Instant timestamp) {
    }

    public record IntegrationStatus(boolean qdrantConfigured, boolean openAiConfigured) {
    }
}
