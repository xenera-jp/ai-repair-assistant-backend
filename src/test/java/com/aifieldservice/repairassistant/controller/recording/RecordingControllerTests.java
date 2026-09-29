package com.aifieldservice.repairassistant.controller.recording;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

class RecordingControllerTests {
    private RecordingService service;
    private MockMvc mockMvc;

    @BeforeEach void setUp() {
        service = mock(RecordingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecordingController(service)).build();
    }

    @Test void uploadsMultipleFilesAndReturnsBatch() throws Exception {
        RecordingViews.Batch batch = batch();
        when(service.create(anyList(), org.mockito.ArgumentMatchers.eq("zh-CN"))).thenReturn(batch);
        MockMultipartFile first = new MockMultipartFile("files", "call.wav", "audio/wav", new byte[]{1,2});
        MockMultipartFile second = new MockMultipartFile("files", "note.mp3", "audio/mpeg", new byte[]{3,4});

        mockMvc.perform(multipart("/api/v1/recording-batches").file(first).file(second).param("language", "zh-CN"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("rb_test"))
                .andExpect(jsonPath("$.files.length()").value(2));
        verify(service).create(anyList(), org.mockito.ArgumentMatchers.eq("zh-CN"));
    }

    @Test void appliesBusinessRoleToFileSpeaker() throws Exception {
        when(service.setSpeakerRole("rf_1", "A", "CUSTOMER_SERVICE")).thenReturn(batch());
        mockMvc.perform(put("/api/v1/recording-files/rf_1/speakers/A/role")
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleCode\":\"CUSTOMER_SERVICE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("rb_test"));
        verify(service).setSpeakerRole("rf_1", "A", "CUSTOMER_SERVICE");
    }

    @Test void streamsRequestedAudioByteRange() throws Exception {
        var audio = Files.createTempFile("recording-controller-", ".mp3");
        try {
            Files.write(audio, new byte[]{10, 20, 30, 40, 50});
            var row = new RecordingRows.File(1, "rf_1", 1, 0, "call.mp3", "unused",
                    "audio/mpeg", 5, "sha", "COMPLETED", null, null);
            when(service.getFile("rf_1")).thenReturn(row);
            when(service.resolveContent(row)).thenReturn(audio);

            mockMvc.perform(get("/api/v1/recording-files/rf_1/content").header("Range", "bytes=1-3"))
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
                List.of(new RecordingViews.File("rf_1", "call.wav", "audio/wav", 2, "TRANSCRIBING", null, null, List.of()),
                        new RecordingViews.File("rf_2", "note.mp3", "audio/mpeg", 2, "UPLOADED", null, null, List.of())),
                List.of(), LocalDateTime.parse("2026-09-15T12:00:00"));
    }
}
