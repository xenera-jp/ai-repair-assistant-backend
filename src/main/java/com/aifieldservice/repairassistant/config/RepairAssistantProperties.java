package com.aifieldservice.repairassistant.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repair-assistant")
public record RepairAssistantProperties(
        Web web,
        Knowledge knowledge,
        Qdrant qdrant,
        OpenAi openai) {

    public record Web(List<String> allowedOrigins) {
    }

    public record Knowledge(String sourcePath, String version, boolean importEnabled) {
    }

    public record Qdrant(String url, String apiKey, String collection) {
    }

    public record OpenAi(
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            int embeddingDimensions) {
    }
}
