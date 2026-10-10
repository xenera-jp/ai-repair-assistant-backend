package com.aifieldservice.repairassistant.controller.recording;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.aifieldservice.repairassistant.service.recording.RealtimeRecordingService;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;

@RestController
@RequestMapping("/api/v1")
public class RealtimeRecordingController {
    public record Frame(long startSample, String audio) {}
    private final RealtimeRecordingService service;
    public RealtimeRecordingController(RealtimeRecordingService service) { this.service = service; }
    @PostMapping("/recording-files/{fileId}/realtime-sessions")
    public RealtimeRecordingService.Started start(@PathVariable String fileId) { return service.start(fileId); }
    @PostMapping("/recording-realtime-sessions/{id}/frames")
    public RealtimeRecordingService.Ack append(@PathVariable String id, @RequestBody Frame frame) { return service.append(id, frame.startSample(), frame.audio()); }
    @PostMapping("/recording-realtime-sessions/{id}/finish")
    public RecordingViews.Batch finish(@PathVariable String id) { return service.finish(id); }
    @PostMapping("/recording-files/{fileId}/diarization-retries")
    public RecordingViews.Batch retry(@PathVariable String fileId) { return service.retryDiarization(fileId); }
    @DeleteMapping("/recording-realtime-sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String id) { service.cancel(id); }
}
