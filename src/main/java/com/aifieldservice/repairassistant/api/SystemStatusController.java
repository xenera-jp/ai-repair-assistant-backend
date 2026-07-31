package com.aifieldservice.repairassistant.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;

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
