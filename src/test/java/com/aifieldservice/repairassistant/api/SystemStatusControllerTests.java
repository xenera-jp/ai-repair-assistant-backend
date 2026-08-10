package com.aifieldservice.repairassistant.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;

class SystemStatusControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RepairAssistantProperties properties = new RepairAssistantProperties(
                new RepairAssistantProperties.Web(List.of("http://localhost")),
                new RepairAssistantProperties.Knowledge("data/knowledge", "test-version", false),
                new RepairAssistantProperties.Qdrant("http://localhost:6333", "", "test-collection"),
                new RepairAssistantProperties.OpenAi(
                        "https://api.openai.com/v1", "", "test-chat", "test-embedding", 1536));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SystemStatusController(properties))
                .build();
    }

    @Test
    void statusOmitsSayHiAndPreservesExistingResponseContract() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sayHi").doesNotExist())
                .andExpect(jsonPath("$.service").value("ai-repair-assistant-backend"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.knowledgeVersion").value("test-version"))
                .andExpect(jsonPath("$.integrations.qdrantConfigured").value(true))
                .andExpect(jsonPath("$.integrations.openAiConfigured").value(false))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$")));
    }
}
