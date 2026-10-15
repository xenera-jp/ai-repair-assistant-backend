package com.aifieldservice.repairassistant.integration.moss;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class MossRecordingGatewayTests {
    @Test void uploadsEntireOriginalFileAndDeliversTimedSegments(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var path=directory.resolve("source.mp3"); java.nio.file.Files.writeString(path,"entire original audio payload");
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var spy=spy(builder); doReturn(spy).when(spy).requestFactory(any());
        var gateway=new MossRecordingGateway(spy,new ObjectMapper(),"https://moss.test/v1","key","model",15,180);
        server.expect(requestTo("https://moss.test/v1/audio/transcriptions")).andExpect(request -> {
            String body=((MockClientHttpRequest)request).getBodyAsString();
            assertTrue(body.contains("filename=\"call.mp3\"")); assertTrue(body.contains("entire original audio payload"));
            assertFalse(body.contains("played-window.wav"));
        }).andRespond(withSuccess(EVENTS,MediaType.TEXT_EVENT_STREAM));
        var received=new ArrayList<com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript>();
        var result=gateway.transcribe(path,"call.mp3",received::add);
        assertEquals(result,received); assertEquals(1200,received.getFirst().endMs()); server.verify();
    }
    @Test void forwardsDeltaBeforeSpeakerSegmentAndBeforeUpstreamRequestCompletes() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var spy=spy(builder); doReturn(spy).when(spy).requestFactory(any());
        var gateway=new MossRecordingGateway(spy,new ObjectMapper(),"https://moss.test/v1","key","model",15,180);
        try(var input=new java.io.PipedInputStream(); var output=new java.io.PipedOutputStream(input)) {
            server.expect(requestTo("https://moss.test/v1/audio/transcriptions")).andRespond(request -> {
                var response=new org.springframework.mock.http.client.MockClientHttpResponse(input,org.springframework.http.HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM); return response;
            });
            var firstDelta=new java.util.concurrent.CountDownLatch(1);
            var deltas=new java.util.concurrent.CopyOnWriteArrayList<String>();
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result=executor.submit(() -> gateway.diarize(new byte[96044],chunk -> {
                    deltas.add(chunk); firstDelta.countDown();
                }));
                output.write("data: {\"type\":\"transcript.text.delta\",\"delta\":\"FH1\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
                assertTrue(firstDelta.await(3,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(java.util.List.of("FH1"),deltas);
                assertFalse(result.isDone(),"Text must reach the frontend callback before speaker confirmation and stream completion");
                output.write("data: {\"type\":\"transcript.segment.done\",\"speaker\":\"S01\",\"start\":0,\"end\":1,\"text\":\"FH1\"}\n\ndata: {\"type\":\"transcript.text.done\",\"text\":\"FH1\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.close();
                assertEquals("S01",result.get(3,java.util.concurrent.TimeUnit.SECONDS).getFirst().speaker());
                server.verify();
            } finally { executor.shutdownNow(); }
        }
    }
    private static final String EVENTS="""
            data: {"type":"transcript.text.delta","delta":"FH1"}

            data: {"type":"transcript.text.delta","delta":"-SSB E4"}

            data: {"type":"transcript.segment.done","speaker":"S01","start":0.1,"end":1.2,"text":"FH1-SSB E4"}

            data: {"type":"transcript.text.done","text":"FH1-SSB E4"}

            """;
    @Test void sendsIndependentCredentialsAndKeytermsAndReadsStreamingSpeakerSegments() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var spy=spy(builder); doReturn(spy).when(spy).requestFactory(any());
        var gateway=new MossRecordingGateway(spy,new ObjectMapper(),"https://moss.test/v1","moss-test-key",
                "moss-transcribe-diarize-pro",15,180);
        server.expect(requestTo("https://moss.test/v1/audio/transcriptions"))
                .andExpect(header("Authorization","Bearer moss-test-key"))
                .andExpect(request -> {
                    String body=((MockClientHttpRequest)request).getBodyAsString();
                    assertTrue(body.contains("moss-transcribe-diarize-pro"));
                    assertTrue(body.contains("verbose_json")); assertTrue(body.contains("name=\"stream\""));
                    assertTrue(body.contains("name=\"diarize\"")); assertTrue(body.contains("[\"FH1-AAC\",\"FH1-SSB\",\"RIR1-SSB\",\"HNC-120AA\",\"E1\",\"E2\",\"E3\",\"E4\",\"E6\",\"E7\",\"E8\",\"E9\",\"E10\",\"CF\"]"));
                    assertFalse(body.contains("known_speaker_references"));
                }).andRespond(withSuccess(EVENTS.stripTrailing(),MediaType.TEXT_EVENT_STREAM));
        var drafts=new ArrayList<String>();
        var result=gateway.diarize(new byte[96044],drafts::add);
        assertEquals(java.util.List.of("FH1","-SSB E4"),drafts);
        assertEquals("S01",result.getFirst().speaker());
        assertEquals(100,result.getFirst().startMs()); assertEquals(1200,result.getFirst().endMs());
        server.verify();
    }
    @Test void rejectsMissingKeyBeforeRequestingAudio() {
        var builder=RestClient.builder(); var json=new ObjectMapper();
        assertThrows(IllegalStateException.class,() -> new MossRecordingGateway(builder,json,"https://moss.test","","model",15,180).validate());
    }
    @Test void rejectsPrematureStreamEndAndErrorEvents() {
        for(String body:java.util.List.of("data: {\"type\":\"transcript.text.delta\",\"delta\":\"partial\"}\n\n",
                "data: {\"type\":\"error\",\"error\":{\"message\":\"secret provider detail\"}}\n\n")) {
            var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
            var spy=spy(builder); doReturn(spy).when(spy).requestFactory(any());
            var gateway=new MossRecordingGateway(spy,new ObjectMapper(),"https://moss.test/v1","key","model",15,180);
            server.expect(requestTo("https://moss.test/v1/audio/transcriptions")).andRespond(withSuccess(body,MediaType.TEXT_EVENT_STREAM));
            var error=assertThrows(IllegalStateException.class,() -> gateway.diarize(new byte[96044],text -> {}));
            assertFalse(error.getMessage().contains("secret provider detail")); server.verify();
        }
    }
}
