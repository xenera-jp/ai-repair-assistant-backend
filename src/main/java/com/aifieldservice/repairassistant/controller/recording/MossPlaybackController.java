package com.aifieldservice.repairassistant.controller.recording;

import org.springframework.web.bind.annotation.*;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.service.recording.MossPlaybackService;

@RestController
@RequestMapping("/api/v1/recording-files/{fileId}/moss-playback")
public class MossPlaybackController {
    public record Position(long positionMs) {}
    private final MossPlaybackService service;
    public MossPlaybackController(MossPlaybackService service) { this.service=service; }
    @PostMapping public RecordingViews.Batch start(@PathVariable String fileId) { return service.start(fileId); }
    @PutMapping public RecordingViews.Batch progress(@PathVariable String fileId,@RequestBody Position position) {
        return service.progress(fileId,position.positionMs(),false);
    }
    @PostMapping("/finish") public RecordingViews.Batch finish(@PathVariable String fileId,@RequestBody Position position) {
        return service.progress(fileId,position.positionMs(),true);
    }
}
