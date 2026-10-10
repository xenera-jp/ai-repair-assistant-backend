package com.aifieldservice.repairassistant.service.recording;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.aifieldservice.repairassistant.dao.recording.DiarizationWindowMapper;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;

class DiarizationWindowsTests {
    @Test void createsBReferenceFromConsecutiveShortSegmentsAndClipsLongAReference() {
        var refs=DiarizationWindows.referenceClips(0,new byte[20*48000],List.of(
                new Transcript("a",0,12000,"A","long agent speech"),
                new Transcript("b1",15000,15800,"B","first short reply"),
                new Transcript("b2",16000,17200,"B","second short reply")));
        assertEquals(Set.of("session_A","session_B"),refs.keySet());
        assertEquals(10*48000+44,Base64.getDecoder().decode(refs.get("session_A").split(",",2)[1]).length);
        assertEquals(2200*48+44,Base64.getDecoder().decode(refs.get("session_B").split(",",2)[1]).length);
    }
    @Test void cannotCreateReferenceAcrossAnotherOrUnknownSpeaker() {
        var refs=DiarizationWindows.referenceClips(0,new byte[10*48000],List.of(
                new Transcript("b1",1000,1800,"B","short"),
                new Transcript("u",1850,1950,"UNKNOWN","interruption"),
                new Transcript("b2",2000,3200,"B","short")));
        assertFalse(refs.containsKey("session_B"));
    }
    private List<Transcript> dialogue() { return List.of(new Transcript("a",0,3000,"x","first speaker"),new Transcript("b",3000,6000,"y","second speaker")); }
    @Test void doesNotRequestUnplayedAudioAndSubmitsTailOnlyOnce() throws Exception {
        var gateway=mock(OpenAiRecordingGateway.class); var mapper=mock(DiarizationWindowMapper.class);
        when(gateway.diarize(any(),eq("ja-JP"),anyMap())).thenReturn(dialogue());
        var service=new DiarizationWindows(gateway,mapper);
        var saves=new AtomicInteger();
        try {
            var state=service.create("session","file","ja-JP",s -> saves.incrementAndGet(),d -> fail(d));
            verifyNoInteractions(gateway,mapper);
            for(int i=0;i<60;i++) state.append(new byte[4800],false);
            verifyNoInteractions(gateway,mapper);
            state.finish().get(3,TimeUnit.SECONDS); state.finish().get(3,TimeUnit.SECONDS);
            verify(gateway,times(1)).diarize(argThat(bytes -> bytes.length==6*48000+44),eq("ja-JP"),anyMap());
            verify(mapper,times(1)).insert("session_0","session","file",0,6000,0);
            assertEquals(1,saves.get());
        } finally {service.close();}
    }
    @Test void failedWindowRetriesSameIdentifierAndBlocksFinishUntilExplicitRetry() throws Exception {
        var gateway=mock(OpenAiRecordingGateway.class); var mapper=mock(DiarizationWindowMapper.class);
        when(gateway.diarize(any(),anyString(),anyMap())).thenThrow(new IllegalStateException("provider failed"));
        var failed=new CountDownLatch(1); var service=new DiarizationWindows(gateway,mapper);
        try {
            var state=service.create("session","file","ja-JP",s -> {},d -> failed.countDown());
            for(int i=0;i<60;i++) state.append(new byte[4800],false);
            var finished=state.finish(); assertTrue(failed.await(3,TimeUnit.SECONDS));
            assertTrue(state.full()); assertFalse(finished.isDone());
            verify(gateway,times(3)).diarize(any(),anyString(),anyMap());
            doReturn(dialogue()).when(gateway).diarize(any(),anyString(),anyMap());
            state.retry(); finished.get(3,TimeUnit.SECONDS);
            verify(mapper,times(1)).insert(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
        } finally {service.close();}
    }
    @Test void boundedBacklogAndCancellationIgnoreInflightResult() throws Exception {
        var gateway=mock(OpenAiRecordingGateway.class); var mapper=mock(DiarizationWindowMapper.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(gateway.diarize(any(),anyString(),anyMap())).thenAnswer(i -> { entered.countDown(); release.await(); return dialogue(); });
        var saves=new AtomicInteger(); var service=new DiarizationWindows(gateway,mapper);
        try {
            var state=service.create("session","file","ja-JP",s -> saves.incrementAndGet(),d -> {});
            for(int i=0;i<390;i++) state.append(new byte[4800],false);
            assertTrue(entered.await(3,TimeUnit.SECONDS)); assertTrue(state.full());
            state.cancel(); release.countDown();
            service.close();
            verify(gateway,timeout(1000).times(1)).diarize(any(),anyString(),anyMap());
            assertEquals(0,saves.get());
        } finally {release.countDown();service.close();}
    }
}
