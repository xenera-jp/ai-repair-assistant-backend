package com.aifieldservice.repairassistant.controller.recording;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.service.recording.RecordingService;
import com.aifieldservice.repairassistant.service.recording.RecordingTranscriptionStream;

class RecordingControllerTests {
    private RecordingService service;
    private MockMvc mockMvc;

    @BeforeEach void setUp() {
        service = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingController(service, new RecordingTranscriptionStream())).build();
    }

    @Test void rejectsMultipleFiles() throws Exception {
        RecordingViews.Batch batch = batch();
        when(service.createRealtime(anyList(), org.mockito.ArgumentMatchers.eq("zh-CN"))).thenReturn(batch);
        MockMultipartFile first = new MockMultipartFile("files", "call.wav", "audio/wav", new byte[]{1,2});
        MockMultipartFile second = new MockMultipartFile("files", "note.mp3", "audio/mpeg", new byte[]{3,4});

        mockMvc.perform(multipart("/api/v1/recording-batches").file(first).file(second).param("language", "zh-CN"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }

    @Test void uploadsOneFileAndReturnsBatch() throws Exception {
        RecordingViews.Batch batch = batch();
        when(service.createRealtime(anyList(), org.mockito.ArgumentMatchers.eq("zh-CN"))).thenReturn(batch);
        MockMultipartFile file = new MockMultipartFile("files", "call.wav", "audio/wav", new byte[]{1,2});

        mockMvc.perform(multipart("/api/v1/recording-batches").file(file).param("language", "zh-CN").param("realtime","false"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("rb_test"))
                .andExpect(jsonPath("$.files.length()").value(1)).andExpect(jsonPath("$.conversationVersion").isString());
        verify(service).createRealtime(anyList(), org.mockito.ArgumentMatchers.eq("zh-CN"));
    }
    @Test void summaryCarriesExplicitConversationVersion() throws Exception {
        when(service.retryExtraction("rb_test","version")).thenReturn(batch());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/recording-batches/rb_test/issue-extractions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"conversationVersion\":\"version\"}"))
                .andExpect(status().isAccepted());
        verify(service).retryExtraction("rb_test","version");
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).retryExtraction("rb_test");
    }

    @Test void appliesBusinessRoleToFileSpeaker() throws Exception {
        when(service.setSpeakerRole("rf_1", "A", "CUSTOMER_SERVICE")).thenReturn(batch());
        mockMvc.perform(put("/api/v1/recording-files/rf_1/speakers/A/role")
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleCode\":\"CUSTOMER_SERVICE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("rb_test"));
        verify(service).setSpeakerRole("rf_1", "A", "CUSTOMER_SERVICE");
    }
    @Test void correctsAllMembersOfMergedTranscriptRow() throws Exception {
        when(service.setSegmentSpeakers("rf_1",List.of("s1","s2"),"B")).thenReturn(batch());
        mockMvc.perform(put("/api/v1/recording-files/rf_1/segments/s1/speaker")
                .contentType(MediaType.APPLICATION_JSON).content("{\"speakerLabel\":\"B\",\"segmentIds\":[\"s1\",\"s2\"]}"))
                .andExpect(status().isOk());
        verify(service).setSegmentSpeakers("rf_1",List.of("s1","s2"),"B");
    }

    @Test void deletesRecordingFileIdempotentlyThroughService() throws Exception {
        mockMvc.perform(delete("/api/v1/recording-files/rf_1"))
                .andExpect(status().isNoContent());
        verify(service).deleteFile("rf_1");
    }

    @Test void streamsRequestedAudioByteRange() throws Exception {
        var audio = Files.createTempFile("recording-controller-", ".mp3");
        try {
            Files.write(audio, new byte[]{10, 20, 30, 40, 50});
            var row = new RecordingRows.File(1, "rf_1", 1, 0, "call.mp3", "unused",
                    "audio/mpeg", 5, "sha", "COMPLETED", null, null, false, null);
            when(service.getFile("rf_1")).thenReturn(row);
            when(service.resolveContent(row)).thenReturn(audio);

            var result = mockMvc.perform(get("/api/v1/recording-files/rf_1/content").header("Range", "bytes=1-3"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string("Content-Range", "bytes 1-3/5"))
                    .andExpect(header().longValue("Content-Length", 3))
                    .andExpect(content().bytes(new byte[]{20, 30, 40}));
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    private RecordingViews.Batch batch() {
        return new RecordingViews.Batch("rb_test", "zh-CN", "TRANSCRIBING", 0, null,
                List.of(new RecordingViews.File("rf_1", "call.wav", "audio/wav", 2, "TRANSCRIBING", null, null, List.of())),
                List.of(), LocalDateTime.parse("2026-09-15T12:00:00"));
    }
}
