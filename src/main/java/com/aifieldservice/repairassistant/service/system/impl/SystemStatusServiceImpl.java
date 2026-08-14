package com.aifieldservice.repairassistant.service.system.impl;

import com.aifieldservice.repairassistant.service.system.*;
import com.aifieldservice.repairassistant.service.system.SystemStatusService.*;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;

/** Builds the application status read model without coupling it to HTTP. */
@Service
public class SystemStatusServiceImpl implements SystemStatusService {

    private final RepairAssistantProperties properties;

    public SystemStatusServiceImpl(RepairAssistantProperties properties) {
        this.properties = properties;
    }

    public SystemStatus status() {
        return new SystemStatus(
                "ai-repair-assistant-backend",
                "UP",
                properties.knowledge().version(),
                new IntegrationStatus(
                        configured(properties.qdrant().url()),
                        configured(properties.openai().apiKey())),
                Instant.now(),
                "sayHi");
    }

    private boolean configured(String value) {
        return value != null && !value.isBlank();
    }


}
