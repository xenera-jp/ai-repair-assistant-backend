package com.aifieldservice.repairassistant.integration.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import tools.jackson.databind.ObjectMapper;

class OpenAiRecordingGatewayTests {
    @TempDir Path directory;

    @Test void acceptsE4CorrectionEvenWhenFirstExtractionMisclassifiedDisplayAsSymptom() {
        for (String type : java.util.List.of("ERROR_CODE", "SYMPTOM")) {
            var fixture = matchingFixture("""
                    {"matches":[{"index":0,"status":"MATCHED","model":"RIR1-SSB","value":"E4","sourceText":"数字'1'和'4'"}]}
                    """);
            var evidence = java.util.List.of("question", "answer");
            var items = java.util.List.of(new OpenAiRecordingGateway.Extracted(type,
                    "面板上显示数字'1'和'4'持续闪烁（客户口述“一四一直在闪”）", evidence));
            var segments = java.util.List.of(
                    new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(
                            1, "question", 1, 0, "A", "CUSTOMER_SERVICE", 1.0, "MODEL", 0, 1000,
                            "收到，r i r e s s b 请问设备面板上现在有没有显示错误代码", "p1"),
                    new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(
                            2, "answer", 1, 1, "B", "CUSTOMER", 1.0, "MODEL", 1000, 2000,
                            "有有有显示一四一直在闪", "p2"));
            var result = fixture.gateway().matchIdentifiers(items, segments, java.util.List.of(
                    new com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService.Candidate(
                            "RIR1-SSB", java.util.List.of("E4"))));
            assertEquals(new OpenAiRecordingGateway.Extracted(type,
                    "SYMPTOM".equals(type) ? "面板上显示E4持续闪烁（客户口述“一四一直在闪”）" : "E4", evidence), result.getFirst());
            fixture.server().verify();
        }
    }

    @Test void synchronizesSymptomCodeWithoutLosingBuzzerOrChangingMeasurements() {
        var fixture = matchingFixture("""
                {"matches":[
                  {"index":0,"status":"MATCHED","model":"RIR1-SSB","value":"E4","sourceText":""},
                  {"index":1,"status":"MATCHED","model":"RIR1-SSB","value":"E4","sourceText":"'14'"}]}
                """);
        var evidence = java.util.List.of("s1");
        var items = java.util.List.of(
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "E4", evidence),
                new OpenAiRecordingGateway.Extracted("SYMPTOM", "面板显示并闪烁异常（显示'14'且持续蜂鸣）；温度14℃", evidence),
                new OpenAiRecordingGateway.Extracted("SYMPTOM", "另一台设备显示14", java.util.List.of("s2")));
        var result = fixture.gateway().matchIdentifiers(items, java.util.List.of(), java.util.List.of(
                new com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService.Candidate(
                        "RIR1-SSB", java.util.List.of("E4"))));
        assertEquals(new OpenAiRecordingGateway.Extracted("SYMPTOM",
                "面板显示并闪烁异常（显示E4且持续蜂鸣）；温度14℃", evidence), result.get(1));
        assertEquals(items.get(0), result.get(0));
        assertEquals(items.get(2), result.get(2));
        fixture.server().verify();
    }

    @Test void matchesCanonicalIdentifiersAndPreservesEvidenceAndOtherFacts() {
        var fixture = matchingFixture("""
                {"matches":[
                  {"index":0,"status":"MATCHED","model":"RIR1-SSB","value":"RIR1-SSB"},
                  {"index":1,"status":"MATCHED","model":"RIR1-SSB","value":"E07"},
                  {"index":3,"status":"UNMATCHED","model":"","value":""}]}
                """);
        var items = java.util.List.of(
                new OpenAiRecordingGateway.Extracted("MODEL", "RIR一 SSB", java.util.List.of("s1", "s2")),
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "E 零七", java.util.List.of("s3")),
                new OpenAiRecordingGateway.Extracted("SYMPTOM", "不制冷", java.util.List.of("s4")),
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "另一台未显示错误码", java.util.List.of("s5")));
        var result = fixture.gateway().matchIdentifiers(items, java.util.List.of(), candidates());
        assertEquals("RIR1-SSB", result.get(0).content());
        assertEquals("E07", result.get(1).content());
        assertEquals(items.get(0).evidenceSegmentIds(), result.get(0).evidenceSegmentIds());
        assertEquals(items.get(2), result.get(2));
        assertEquals(items.get(3), result.get(3));
        fixture.server().verify();
    }

    @Test void acceptsExplicitAgentRestatementOnlyWithinSameModelCandidates() {
        var fixture=matchingFixture("""
                {"matches":[{"index":0,"status":"MATCHED","model":"RIR1-SSB","value":"E3","sourceText":""}]}
                """);
        var item=new OpenAiRecordingGateway.Extracted("ERROR_CODE","1、3",java.util.List.of("customer","agent"));
        var segments=java.util.List.of(
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(1,"customer",1,0,"B","CUSTOMER",1.0,"MODEL",0,1000,"表示は1、3です",null),
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(2,"agent",1,1,"A","CUSTOMER_SERVICE",1.0,"MODEL",1000,2000,"E3の点滅ですね",null));
        var result=fixture.gateway().matchIdentifiers(java.util.List.of(item),segments,java.util.List.of(
                new com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService.Candidate("RIR1-SSB",java.util.List.of("E3","E4"))));
        assertEquals("E3",result.getFirst().content());
        fixture.server().verify();
    }

    @Test void rejectsInventedOrWrongModelCodesAndIncompleteMatches() {
        for (String matches : java.util.List.of(
                "[{\"index\":0,\"status\":\"MATCHED\",\"model\":\"RIR1-SSB\",\"value\":\"E99\"}]",
                "[{\"index\":0,\"status\":\"MATCHED\",\"model\":\"UNKNOWN\",\"value\":\"E07\"}]",
                "[]",
                "[{\"index\":0,\"status\":\"UNMATCHED\"},{\"index\":0,\"status\":\"UNMATCHED\"}]")) {
            var fixture = matchingFixture("{\"matches\":" + matches + "}");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> fixture.gateway().matchIdentifiers(java.util.List.of(
                            new OpenAiRecordingGateway.Extracted("ERROR_CODE", "E 零七", java.util.List.of("s1"))),
                            java.util.List.of(), candidates()));
            fixture.server().verify();
        }
    }

    private java.util.List<com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService.Candidate> candidates() {
        return java.util.List.of(new com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService.Candidate(
                "RIR1-SSB", java.util.List.of("E07")));
    }

    private record MatchingFixture(OpenAiRecordingGateway gateway, MockRestServiceServer server) {}

    private MatchingFixture matchingFixture(String output) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var mockBuilder = org.mockito.Mockito.spy(builder);
        org.mockito.Mockito.doReturn(mockBuilder).when(mockBuilder).requestFactory(org.mockito.ArgumentMatchers.any());
        var properties = new RepairAssistantProperties(null, null, null,
                new RepairAssistantProperties.OpenAi("https://recording.test/v1", "test-key", "unused", "unused", 1), null);
        var json = new ObjectMapper();
        server.expect(requestTo("https://recording.test/v1/responses"))
                .andExpect(request -> {
                    var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("unused", body.path("model").asText());
                    assertEquals("recording_identifier_matches", body.path("text").path("format").path("name").asText());
                    assertTrue(body.path("input").asText().contains("RIR1-SSB"));
                    assertTrue(body.path("input").asText().contains("明确复述或纠正"));
                })
                .andRespond(withSuccess(json.writeValueAsString(java.util.Map.of("output", java.util.List.of(
                        java.util.Map.of("content", java.util.List.of(java.util.Map.of("text", output)))))), MediaType.APPLICATION_JSON));
        return new MatchingFixture(new OpenAiRecordingGateway(properties, mockBuilder, json), server);
    }

    @Test void sendsMultipartAudioAndParsesDiarizedResponse() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        // Apply the mock transport after the gateway configures its production request factory.
        builder = builder.clone().apply(b -> {});
        var properties = new RepairAssistantProperties(null, null, null,
                new RepairAssistantProperties.OpenAi("https://recording.test/v1", "test-key", "unused", "unused", 1), null);
        var mockBuilder = org.mockito.Mockito.spy(builder);
        org.mockito.Mockito.doReturn(mockBuilder).when(mockBuilder).requestFactory(org.mockito.ArgumentMatchers.any());
        var gateway = new OpenAiRecordingGateway(properties, mockBuilder, new ObjectMapper());
        server.expect(requestTo("https://recording.test/v1/audio/transcriptions"))
                .andExpect(request -> {
                    assertTrue(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("filename=\"call.wav\""));
                    assertTrue(body.contains("Content-Type: audio/wav"));
                    assertTrue(body.contains("test-audio-content"));
                    assertTrue(body.contains("gpt-4o-transcribe-diarize"));
                    assertTrue(body.contains("diarized_json"));
                    assertTrue(body.contains("name=\"chunking_strategy\""));
                    assertTrue(body.contains("\r\n\r\nauto\r\n"));
                    assertTrue(body.contains("name=\"language\""));
                    assertTrue(body.contains("\r\n\r\nja\r\n"));
                    assertTrue(body.contains("name=\"temperature\""));
                })
                .andRespond(withSuccess("{\"segments\":[{\"id\":\"s1\",\"start\":0.25,\"end\":1.5,\"speaker\":\"A\",\"text\":\"hello\"}]}", MediaType.APPLICATION_JSON));
        Path audio = Files.writeString(directory.resolve("stored.wav"), "test-audio-content");
        var result = gateway.transcribe(audio, "call.wav", "audio/wav", "ja-JP");
        assertEquals(1, result.size());
        assertEquals(new OpenAiRecordingGateway.Transcript("s1", 250, 1500, "A", "hello"), result.getFirst());
        server.verify();
    }

    @Test void repairsInvalidAndTinySandwichedSpeakerLabels() {
        var normalized = OpenAiRecordingGateway.normalizeSpeakerLabels(java.util.List.of(
                new OpenAiRecordingGateway.Transcript("s1",0,1000,"A","確認します"),
                new OpenAiRecordingGateway.Transcript("s2",1000,1800,"@","ね"),
                new OpenAiRecordingGateway.Transcript("s3",1800,2800,"A","エラーです"),
                new OpenAiRecordingGateway.Transcript("s4",2800,3800,"B","はい"),
                new OpenAiRecordingGateway.Transcript("s5",3800,4500,"C","うん"),
                new OpenAiRecordingGateway.Transcript("s6",4500,5500,"B","そうです")));
        assertEquals("A", normalized.get(1).speaker());
        assertEquals("B", normalized.get(4).speaker());
    }

}
