package com.aifieldservice.repairassistant.service.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.aifieldservice.repairassistant.dao.knowledge.EquipmentIdentifierMapper;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import tools.jackson.databind.ObjectMapper;

class EquipmentIdentifierMatchingServiceTests {
    @Test void combinesKnowledgeSourcesWithoutMixingModelCodes() {
        var mapper = mock(EquipmentIdentifierMapper.class);
        var gateway = mock(OpenAiRecordingGateway.class);
        var service = new EquipmentIdentifierMatchingService(mapper, gateway, new ObjectMapper());
        when(mapper.listIdentifiers()).thenReturn(List.of(
                new EquipmentIdentifierMapper.Row("A", "[\"E01\",null,\"\"]"),
                new EquipmentIdentifierMapper.Row("A", "[\"E01\",\"E02\"]"),
                new EquipmentIdentifierMapper.Row("B", "[\"E09\"]")));
        var items = List.of(new OpenAiRecordingGateway.Extracted("MODEL", "a", List.of("s1")));
        var result = service.match(items, List.of());
        assertEquals(List.of(new OpenAiRecordingGateway.Extracted("MODEL", "A", List.of("s1"))), result.items());
        assertEquals("RULE_AUTO_CORRECTED", result.decisions().getFirst().status());
        verifyNoInteractions(gateway);
    }

    @Test void skipsAiWhenKnowledgeIsEmptyOrThereAreNoIdentifiers() {
        var mapper = mock(EquipmentIdentifierMapper.class);
        var gateway = mock(OpenAiRecordingGateway.class);
        var service = new EquipmentIdentifierMatchingService(mapper, gateway, new ObjectMapper());
        var symptoms = List.of(new OpenAiRecordingGateway.Extracted("ENVIRONMENT", "环境温度高", List.of("s1")));
        assertEquals(symptoms, service.match(symptoms, List.of()).items());
        verifyNoInteractions(mapper, gateway);
        var model = List.of(new OpenAiRecordingGateway.Extracted("MODEL", "A", List.of("s1")));
        when(mapper.listIdentifiers()).thenReturn(List.of());
        assertEquals(model, service.match(model, List.of()).items());
        verifyNoInteractions(gateway);
    }

    @Test void normalizesSpokenDigitsAndKeepsOrderedSimilarity() {
        assertEquals("E07", EquipmentIdentifierMatchingService.normalize("Ｅ 零七"));
        assertEquals(1.0, EquipmentIdentifierMatchingService.ratio("E07", "E07"));
        assertEquals(true, EquipmentIdentifierMatchingService.ratio("E07", "E70") < 1.0);
        assertEquals("14", EquipmentIdentifierMatchingService.identifierSource(
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "面板显示 “14” 一直在闪，并伴随蜂鸣。", List.of("s2"))));
        assertEquals("RIR1 / riressb", EquipmentIdentifierMatchingService.identifierSource(
                new OpenAiRecordingGateway.Extracted("MODEL", "机型：客户口述“RIR1”，随后拼读为“riressb”。", List.of("s1"))));
    }

    @Test void pendingModelStillScopesAndCreatesSeparateErrorCodeConfirmation() {
        var mapper = mock(EquipmentIdentifierMapper.class);
        var gateway = mock(OpenAiRecordingGateway.class);
        var service = new EquipmentIdentifierMatchingService(mapper, gateway, new ObjectMapper());
        when(mapper.listIdentifiers()).thenReturn(List.of(
                new EquipmentIdentifierMapper.Row("RIR1-SSB", "[\"E4\",\"E07\"]")));
        var items = List.of(
                new OpenAiRecordingGateway.Extracted("MODEL", "RZZX SSB ???", List.of("s1")),
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "面板显示 “14” 一直在闪，并伴随蜂鸣。", List.of("s2")));
        var segments = List.of(
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(
                        1,"s1",9,0,"A","CUSTOMER",1.0,"MODEL",0,1000,"型号是 RIR1 SSB",null),
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(
                        2,"s2",9,1,"A","CUSTOMER",1.0,"MODEL",1000,2000,"错误码面板显示十四",null));
        when(gateway.matchIdentifiers(anyList(), anyList(), anyList())).thenAnswer(invocation -> {
            List<OpenAiRecordingGateway.Extracted> request = invocation.getArgument(0);
            var item = request.getFirst();
            return List.of(new OpenAiRecordingGateway.Extracted(item.type(),
                    "MODEL".equals(item.type()) ? "RIR1-SSB" : "E4", item.evidenceSegmentIds()));
        });

        var result = service.match(items, segments);

        assertEquals(2, result.decisions().size());
        assertEquals("LLM_PENDING_CONFIRMATION", result.decisions().get(0).status());
        assertEquals("LLM_PENDING_CONFIRMATION", result.decisions().get(1).status());
        assertEquals("E4", result.decisions().get(1).suggestedValue());
        assertEquals("14", result.decisions().get(1).sourceText());
        assertEquals("RIR1-SSB", result.decisions().get(1).model());
    }

    @Test void unresolvedErrorCodeKeepsOnlyObservedPanelTokenInsteadOfNarrative() {
        var mapper = mock(EquipmentIdentifierMapper.class);
        var gateway = mock(OpenAiRecordingGateway.class);
        var service = new EquipmentIdentifierMatchingService(mapper, gateway, new ObjectMapper());
        when(mapper.listIdentifiers()).thenReturn(List.of(new EquipmentIdentifierMapper.Row("RIR1-SSB", "[\"E4\"]")));
        var items = List.of(
                new OpenAiRecordingGateway.Extracted("MODEL", "RIR1-SSB", List.of("s1")),
                new OpenAiRecordingGateway.Extracted("ERROR_CODE", "面板显示：客户报告で「1、3」が点滅している。CSが『E3』と発言。", List.of("s2")));
        var segments = List.of(
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(1,"s1",9,0,"A","CUSTOMER",1.0,"MODEL",0,1000,"型式 RIR1-SSB",null),
                new com.aifieldservice.repairassistant.domain.recording.model.RecordingRows.Segment(2,"s2",9,1,"B","CUSTOMER",1.0,"MODEL",1000,2000,"表示は1、3です",null));
        when(gateway.matchIdentifiers(anyList(),anyList(),anyList())).thenAnswer(invocation->invocation.getArgument(0));

        var result=service.match(items,segments);

        assertEquals("1、3",result.items().get(1).content());
        assertEquals("MANUAL_INPUT_REQUIRED",result.decisions().get(1).status());
        assertEquals("1、3",result.decisions().get(1).sourceText());
    }
}
