package com.aifieldservice.repairassistant.service.recording;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRealtimeGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RealtimeRecordingServiceTests {
    private RecordingService recordings;
    private OpenAiRealtimeGateway gateway;
    private RecordingTranscriptionStream stream;
    private RealtimeRecordingService service;
    private Consumer<JsonNode> event;
    private final List<Map<String, ?>> sent = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private String id;
    private DiarizationWindows.State state;

    @BeforeEach void setup() {
        recordings = mock(RecordingService.class); gateway = mock(OpenAiRealtimeGateway.class); stream = mock(RecordingTranscriptionStream.class);
        var batch = new RecordingViews.Batch("batch", "ja-JP", "TRANSCRIBING", 0, null, List.of(), List.of(), LocalDateTime.now());
        when(recordings.beginRealtime("file")).thenReturn(batch);
        when(recordings.getBatch("batch")).thenReturn(batch);
        doAnswer(invocation -> {
            event = invocation.getArgument(1);
            return new OpenAiRealtimeGateway.Connection() {
                public void send(Map<String, ?> payload) { sent.add(payload); }
                public void close() {}
            };
        }).when(gateway).connect(eq("ja-JP"), any(), any());
        var properties = new RepairAssistantProperties(null, null, null, null, null);
        var windows=mock(DiarizationWindows.class);
        state=mock(DiarizationWindows.State.class);
        when(windows.create(anyString(),anyString(),anyString(),any(),any())).thenReturn(state);
        when(state.finish()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        service = new RealtimeRecordingService(recordings, gateway, stream, properties, windows);
        id = service.start("file").sessionId();
    }
    @AfterEach void cleanup() { service.close(); }

    private void emit(Map<String, ?> payload) { event.accept(json.readTree(json.writeValueAsString(payload))); }
    private void voicedTurn(long start) {
        for (int i = 0; i < 12; i++) service.append(id, start + i*2400L, tone(i, 120));
        for (int i = 12; i < 18; i++) service.append(id, start + i*2400L, Base64.getEncoder().encodeToString(new byte[4800]));
    }
    private static String tone(int frame, double hz) {
        var bytes = ByteBuffer.allocate(4800).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2400; i++) bytes.putShort((short)(10000*Math.sin(2*Math.PI*hz*(frame*2400+i)/24000)));
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    @Test void startsWithoutSendingAudioAndUsesOneConnectionAcrossInputGaps() {
        assertTrue(sent.isEmpty());
        service.append(id, 0, tone(0,120));
        service.append(id, 2400, tone(1,120));
        verify(gateway, times(1)).connect(eq("ja-JP"), any(), any());
        assertEquals(2, sent.size());
    }
    @Test void duplicateFrameAcknowledgesWithoutResendingAndRejectsOutOfOrder() {
        String audio = tone(0,120);
        assertEquals(2400, service.append(id,0,audio).nextSample());
        assertEquals(2400, service.append(id,0,audio).nextSample());
        assertEquals(1, sent.size());
        assertThrows(ResponseStatusException.class, () -> service.append(id,100,audio));
    }
    @Test void commitsMapItemsBeforeOutOfOrderCompletion() {
        voicedTurn(0); emit(Map.of("type","input_audio_buffer.committed","item_id","first"));
        voicedTurn(43200); emit(Map.of("type","input_audio_buffer.committed","item_id","second"));
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","second","transcript","二番目"));
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","first","transcript","最初"));
        verify(stream).publishDraft("batch","file","first",0L,1800L,"最初");
        verify(stream).publishDraft("batch","file","second",1800L,3600L,"二番目");
    }
    @Test void earlyCompletionWaitsForCommitAndDuplicateCompletionIsIgnored() {
        voicedTurn(0);
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","first","transcript","はい"));
        verify(recordings, never()).saveRealtimeSegment(anyString(),anyString(),anyInt(),anyLong(),anyLong(),anyString(),anyString());
        emit(Map.of("type","input_audio_buffer.committed","item_id","first"));
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","first","transcript","はい"));
        verify(stream,times(1)).publishDraft("batch","file","first",0L,1800L,"はい");
    }
    @Test void finishWaitsForFinalTextBeforeRoleInference() {
        for (int i = 0; i < 12; i++) service.append(id, i*2400L, tone(i,120));
        service.finish(id);
        verify(recordings, never()).finishRealtime(anyString());
        emit(Map.of("type","input_audio_buffer.committed","item_id","tail"));
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","tail","transcript","終わり"));
        verify(recordings, timeout(2000)).finishRealtime("file");
    }
    @Test void rejectsMalformedAndOversizedAudioBeforeUpstreamSend() {
        assertThrows(ResponseStatusException.class, () -> service.append(id,0,"%%%"));
        assertThrows(ResponseStatusException.class, () -> service.append(id,0,Base64.getEncoder().encodeToString(new byte[5000])));
        assertTrue(sent.isEmpty());
    }
    @Test void cancellationPreventsLaterResultsAndDoesNotStartExtraction() {
        service.cancel(id);
        emit(Map.of("type","conversation.item.input_audio_transcription.completed","item_id","late","transcript","遅延"));
        verify(recordings, never()).saveRealtimeSegment(anyString(),anyString(),anyInt(),anyLong(),anyLong(),anyString(),anyString());
        verify(recordings, never()).retryExtraction(anyString());
        assertThrows(ResponseStatusException.class, () -> service.append(id,0,tone(0,120)));
    }
    private List<RealtimeRecordingService.Frame> frames(long start, int count) {
        var result = new ArrayList<RealtimeRecordingService.Frame>();
        for (int i=0; i<count; i++) result.add(new RealtimeRecordingService.Frame(start+i*2400L, tone(i,120)));
        return result;
    }
    @Test void batchPreservesIndividualFramesAndLostResponseRetriesAreIdempotent() {
        var frames = frames(0,5);
        assertEquals(new RealtimeRecordingService.BatchAck(12000,false),service.appendBatch(id,frames));
        assertEquals(5,sent.size());
        assertEquals(new RealtimeRecordingService.BatchAck(12000,false),service.appendBatch(id,frames));
        assertEquals(5,sent.size());
        assertEquals(14400,service.appendBatch(id,frames(12000,1)).nextSample());
        assertEquals(6,sent.size());
    }
    @Test void validatesEntireBatchBeforeSendingAnyAudio() {
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,frames(0,6)));
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,List.of()));
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,List.of(
                new RealtimeRecordingService.Frame(0,tone(0,120)),new RealtimeRecordingService.Frame(2400,"%%%"))));
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,List.of(
                new RealtimeRecordingService.Frame(0,tone(0,120)),new RealtimeRecordingService.Frame(2500,tone(1,120)))));
        assertTrue(sent.isEmpty());
    }
    @Test void partialBatchAcknowledgesAcceptedPrefixAndRetriesDoNotDuplicateIt() {
        when(state.full()).thenReturn(false,true);
        var frames=frames(0,3);
        var ack=service.appendBatch(id,frames);
        assertEquals(new RealtimeRecordingService.BatchAck(2400,true),ack);
        assertEquals(1,sent.size());
        assertEquals(ack,service.appendBatch(id,frames));
        assertEquals(1,sent.size());
        when(state.full()).thenReturn(false);
        assertEquals(new RealtimeRecordingService.BatchAck(7200,false),service.appendBatch(id,frames.subList(1,3)));
        assertEquals(3,sent.size());
    }
    @Test void zeroProgressBackpressureCanBeRetriedAfterCapacityReturns() {
        when(state.full()).thenReturn(true);
        var frames=frames(0,2);
        assertEquals(new RealtimeRecordingService.BatchAck(0,true),service.appendBatch(id,frames));
        assertTrue(sent.isEmpty());
        when(state.full()).thenReturn(false);
        assertEquals(new RealtimeRecordingService.BatchAck(4800,false),service.appendBatch(id,frames));
        assertEquals(2,sent.size());
    }
    @Test void batchRechecksFileVisibilityAndCancellation() {
        when(recordings.getFile("file")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,frames(0,1)));
        assertTrue(sent.isEmpty());
        reset(recordings);
        service.cancel(id);
        assertThrows(ResponseStatusException.class,()->service.appendBatch(id,frames(0,1)));
    }
}
