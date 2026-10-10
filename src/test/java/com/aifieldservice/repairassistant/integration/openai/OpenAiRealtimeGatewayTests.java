package com.aifieldservice.repairassistant.integration.openai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import tools.jackson.databind.ObjectMapper;

class OpenAiRealtimeGatewayTests {
    @Test void modelComesFromSharedConfigurationAndNoKeyAppearsInSessionPayload() {
        var recording = new RepairAssistantProperties.Recording("unused",100,1,1,"configured-realtime-model",0.75,1,15,30);
        var properties = new RepairAssistantProperties(null,null,null,
                new RepairAssistantProperties.OpenAi("https://api.openai.com/v1","test-secret","chat-model","unused",1),recording,null);
        var gateway = new OpenAiRealtimeGateway(properties);
        var json = new ObjectMapper();
        String payload = json.writeValueAsString(gateway.configuration("ja-JP"));
        var input = json.readTree(payload).path("session").path("audio").path("input");
        assertEquals("configured-realtime-model",input.path("transcription").path("model").asText());
        assertEquals("ja",input.path("transcription").path("languages").get(0).asText());
        assertEquals(24000,input.path("format").path("rate").asInt());
        assertTrue(input.path("turn_detection").isNull());
        assertFalse(payload.contains("test-secret"));
    }
}
