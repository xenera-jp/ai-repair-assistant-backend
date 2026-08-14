package com.aifieldservice.repairassistant.domain.diagnosis.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DiagnosisSnapshotCompatibilityTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsAndWritesExistingDiagnosisSessionSnapshotShape() {
        String legacySnapshot = """
                {
                  "id":"session-1",
                  "stage":"ONSITE",
                  "status":"CONVERGED",
                  "progress":{"phase":"DONE","percent":100},
                  "problemUnderstanding":{
                    "id":"understanding-1",
                    "originalText":"RIR1-SSB E6",
                    "language":"zh-CN",
                    "summary":"summary",
                    "primaryProblemType":{"code":"FAN_FAILURE","label":"风机故障","supportScore":0.92},
                    "fields":[],
                    "readyForAnalysis":true,
                    "blockingMessage":null
                  },
                  "candidates":[],
                  "evidenceGroups":[],
                  "recommendations":null,
                  "nextQuestion":null,
                  "updatedAt":"2026-08-11T00:00:00Z"
                }
                """;

        DiagnosisSession session = objectMapper.readValue(
                legacySnapshot, DiagnosisSession.class);
        JsonNode rewritten = objectMapper.readTree(objectMapper.writeValueAsString(session));

        assertThat(session.id()).isEqualTo("session-1");
        assertThat(session.problemUnderstanding().primaryProblemType().supportScore()).isEqualTo(0.92);
        assertThat(rewritten.path("id").asText()).isEqualTo("session-1");
        assertThat(rewritten.path("problemUnderstanding").path("primaryProblemType").path("code").asText())
                .isEqualTo("FAN_FAILURE");
        assertThat(rewritten.path("updatedAt").asText()).isEqualTo("2026-08-11T00:00:00Z");
        assertThat(rewritten.path("recommendations").isNull()).isTrue();
    }
}
