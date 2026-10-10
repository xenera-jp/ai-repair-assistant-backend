package com.aifieldservice.repairassistant.controller.recording;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.aifieldservice.repairassistant.service.recording.RealtimeRecordingService;

class RealtimeRecordingControllerTests {
    @Test void batchPayloadAndPartialBackpressureAckRoundTrip() throws Exception {
        var service=mock(RealtimeRecordingService.class);
        var frames=List.of(new RealtimeRecordingService.Frame(0,"AAA="),new RealtimeRecordingService.Frame(1,"AAA="));
        when(service.appendBatch("session",frames)).thenReturn(new RealtimeRecordingService.BatchAck(1,true));
        var mvc=MockMvcBuilders.standaloneSetup(new RealtimeRecordingController(service)).build();
        mvc.perform(post("/api/v1/recording-realtime-sessions/session/frame-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"startSample\":0,\"audio\":\"AAA=\"},{\"startSample\":1,\"audio\":\"AAA=\"}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextSample").value(1))
                .andExpect(jsonPath("$.blocked").value(true));
        verify(service).appendBatch("session",frames);
    }
    @Test void originalSingleFrameEndpointRemainsAvailable() throws Exception {
        var service=mock(RealtimeRecordingService.class);
        when(service.append("session",0,"AAA=")).thenReturn(new RealtimeRecordingService.Ack(1));
        var mvc=MockMvcBuilders.standaloneSetup(new RealtimeRecordingController(service)).build();
        mvc.perform(post("/api/v1/recording-realtime-sessions/session/frames")
                .contentType(MediaType.APPLICATION_JSON).content("{\"startSample\":0,\"audio\":\"AAA=\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextSample").value(1));
        verify(service).append("session",0,"AAA=");
    }
}