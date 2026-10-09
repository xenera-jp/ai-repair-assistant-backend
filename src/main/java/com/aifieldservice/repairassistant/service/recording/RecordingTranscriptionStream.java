package com.aifieldservice.repairassistant.service.recording;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;

/** Bridges background transcription events to connected recording pages. */
@Component
public class RecordingTranscriptionStream {
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String batchId, Supplier<RecordingViews.Batch> initial) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitters.computeIfAbsent(batchId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> remove(batchId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("batch").data(initial.get()));
        } catch (IOException | RuntimeException error) {
            remove.run();
            emitter.completeWithError(error);
        }
        return emitter;
    }

    public void publishBatch(String batchId, RecordingViews.Batch batch) {
        send(batchId, "batch", batch);
    }

    public void publishDelta(String batchId, String fileId, String segmentId, String delta) {
        send(batchId, "delta", Map.of("fileId", fileId, "segmentId", segmentId, "delta", delta));
    }

    public void publishDeleted(String batchId, String fileId) {
        send(batchId, "resource-deleted", Map.of("fileId", fileId));
    }

    private void send(String batchId, String eventName, Object data) {
        var current = emitters.get(batchId);
        if (current == null) return;
        for (SseEmitter emitter : current) {
            try { emitter.send(SseEmitter.event().name(eventName).data(data)); }
            catch (Exception ignored) { remove(batchId, emitter); emitter.complete(); }
        }
    }

    private void remove(String batchId, SseEmitter emitter) {
        var current = emitters.get(batchId);
        if (current == null) return;
        current.remove(emitter);
        if (current.isEmpty()) emitters.remove(batchId, current);
    }
}
