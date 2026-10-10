package com.aifieldservice.repairassistant.service.recording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRealtimeGateway;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import tools.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;

/** Bounded HTTP PCM ingress, one persistent upstream WebSocket and existing SSE egress. */
@Service
public class RealtimeRecordingService {
    public record Started(String sessionId, RecordingViews.Batch batch) {}
    public record Ack(long nextSample) {}
    public record Turn(int sequence, long startMs, long endMs, String speaker) {}
    private final RecordingService recordings;
    private final OpenAiRealtimeGateway gateway;
    private final RecordingTranscriptionStream stream;
    private final RepairAssistantProperties properties;
    private final DiarizationWindows diarization;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(8);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    public RealtimeRecordingService(RecordingService recordings, OpenAiRealtimeGateway gateway,
            RecordingTranscriptionStream stream, RepairAssistantProperties properties, DiarizationWindows diarization) {
        this.recordings = recordings; this.gateway = gateway; this.stream = stream; this.properties = properties;
        this.diarization = diarization;
        timer.scheduleAtFixedRate(() -> sessions.values().forEach(session -> {
            if (System.nanoTime() - session.activity > TimeUnit.MINUTES.toNanos(5)) session.fail("实时会话空闲超时，请重新演示。");
            else try { recordings.getFile(session.fileId); } catch (Exception e) { session.cancel(); }
        }), 30, 30, TimeUnit.SECONDS);
    }

    public Started start(String fileId) {
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "实时会话繁忙，请稍后重试。");
        RecordingViews.Batch batch;
        try { batch = recordings.beginRealtime(fileId); }
        catch (RuntimeException e) { capacity.release(); throw e; }
        Session session = new Session(fileId, batch.id());
        sessions.put(session.id, session);
        try {
            session.connection = gateway.connect(batch.language(), session::event, session::fail);
            if (session.closed) { session.connection.close(); throw new IllegalStateException("实时连接已关闭。"); }
            return new Started(session.id, batch);
        } catch (Exception e) { session.fail(e.getMessage()); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage()); }
    }

    public Ack append(String id, long startSample, String audio) {
        Session session = required(id);
        recordings.getFile(session.fileId); // Recheck visibility/deletion before accepting any new input.
        return session.append(startSample, audio);
    }

    public RecordingViews.Batch finish(String id) {
        Session session = required(id);
        session.finish();
        return recordings.getBatch(session.batchId);
    }

    public void cancel(String id) { Session session = sessions.get(id); if (session != null) session.fail("实时演示已中断，请重新演示。"); }
    public RecordingViews.Batch retryDiarization(String fileId) {
        Session session=sessions.values().stream().filter(s -> s.fileId.equals(fileId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"会话已失效，请重头演示。"));
        synchronized(session) { recordings.resumeDiarization(fileId); session.windows.retry(); session.activity=System.nanoTime(); }
        return recordings.getBatch(session.batchId);
    }
    private Session required(String id) {
        Session session = sessions.get(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "实时会话已失效，请重新演示。");
        return session;
    }
    @PreDestroy public void close() { sessions.values().forEach(Session::cancel); timer.shutdownNow(); }

    final class Session {
        final String id = "rt_" + UUID.randomUUID(), fileId, batchId;
        final DiarizationWindows.State windows;
        final ArrayDeque<Turn> pending = new ArrayDeque<>();
        final Map<String, Turn> items = new ConcurrentHashMap<>();
        final Map<String, String> earlyFinal = new HashMap<>();
        final java.util.Set<String> completed = ConcurrentHashMap.newKeySet();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        volatile OpenAiRealtimeGateway.Connection connection;
        volatile long activity = System.nanoTime();
        volatile boolean closed;
        boolean finishing;
        long samples, turnStart, previousStart = -1;
        String previousAudio;
        int nextSequence, committed, finalized;
        long silenceSamples;
        boolean turnSpeech;
        volatile long confirmedUntil;
        Session(String fileId, String batchId) {
            this.fileId = fileId; this.batchId = batchId;
            windows=diarization.create(id,fileId,recordings.getBatch(batchId).language(),
                    confirmed -> {
                        if(closed) return;
                        recordings.replaceConfirmed(fileId,confirmed);
                        confirmedUntil=confirmed.stream().mapToLong(s -> s.endMs()).max().orElse(confirmedUntil);
                        items.forEach((item,turn) -> {
                            if(completed.contains(item) && turn.endMs()<=confirmedUntil) stream.publishFinal(batchId,fileId,item);
                        });
                    },
                    detail -> { if(!closed) recordings.diarizationFailed(fileId,detail); });
        }

        synchronized Ack append(long start, String audio) {
            if (closed || finishing) throw new ResponseStatusException(HttpStatus.CONFLICT, "实时输入已结束。");
            if (start == previousStart && audio != null && audio.equals(previousAudio)) return new Ack(samples);
            if (start != samples) throw new ResponseStatusException(HttpStatus.CONFLICT, "音频帧顺序不一致，请重新演示。");
            if(samples>=24000L*60*120) {
                fail("演示超过两小时，请使用较短录音。");
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"演示超过两小时。");
            }
            byte[] bytes;
            try {
                if (audio == null || audio.length() > 6400) throw new IllegalArgumentException();
                bytes = Base64.getDecoder().decode(audio);
                if (bytes.length == 0 || bytes.length > 4800 || bytes.length % 2 != 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "音频帧必须为不超过100毫秒的24kHz PCM16。"); }
            if (committed - finalized >= 8 || windows.full()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "转写或分离结果积压，请暂停后继续。");
            short[] pcm = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            double energy=0; for(short value:pcm) energy+=(double)value*value;
            boolean silence=Math.sqrt(energy/pcm.length)<300;
            if(silence) silenceSamples+=pcm.length; else { silenceSamples=0; turnSpeech=true; }
            try {
                connection.send(Map.of("type", "input_audio_buffer.append", "audio", audio));
                previousStart = start; previousAudio = audio; samples += pcm.length; activity = System.nanoTime();
                windows.append(bytes, silence);
                long length = samples - turnStart;
                if (length >= 2400 && (silenceSamples >= 14400 || length >= 192000)) commit();
                return new Ack(samples);
            } catch (RuntimeException e) { fail("音频发送失败，请重新演示。"); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "音频发送失败，请重新演示。"); }
        }

        private void commit() {
            long length = samples - turnStart;
            if (length == 0) return;
            if (!turnSpeech) {
                connection.send(Map.of("type", "input_audio_buffer.clear")); turnStart = samples; return;
            }
            if (length < 2400) connection.send(Map.of("type", "input_audio_buffer.append", "audio",
                    Base64.getEncoder().encodeToString(new byte[(int)(2400 - length) * 2])));
            Turn turn = new Turn(nextSequence++, turnStart * 1000 / 24000, samples * 1000 / 24000, "UNKNOWN");
            turnSpeech=false;
            pending.add(turn); committed++;
            connection.send(Map.of("type", "input_audio_buffer.commit")); turnStart = samples;
        }

        synchronized void event(JsonNode event) {
            if (closed) return;
            String type = event.path("type").asText(), itemId = event.path("item_id").asText();
            try {
                if ("input_audio_buffer.committed".equals(type)) {
                    if (items.containsKey(itemId)) return;
                    Turn turn = pending.poll();
                    if (turn == null || itemId.isBlank()) { fail("转写输入项关联失败，请重新演示。"); return; }
                    items.put(itemId, turn);
                    String text = earlyFinal.remove(itemId); if (text != null) complete(itemId, text);
                } else if ("conversation.item.input_audio_transcription.delta".equals(type)) {
                    Turn turn=items.get(itemId);
                    if (!completed.contains(itemId) && (turn==null || turn.endMs()>confirmedUntil)) stream.publishDelta(batchId, fileId, itemId, event.path("delta").asText());
                } else if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                    if (items.containsKey(itemId)) complete(itemId, event.path("transcript").asText());
                    else { if (earlyFinal.size() >= 8) throw new IllegalStateException(); earlyFinal.put(itemId, event.path("transcript").asText()); }
                } else if ("conversation.item.input_audio_transcription.failed".equals(type)) fail("部分音频转写失败，请重新演示。");
            } catch (Exception e) { fail("转写结果保存或关联失败，请重新演示。"); }
        }

        private void complete(String itemId, String text) {
            if (completed.contains(itemId)) return;
            Turn turn = items.get(itemId);
            // Draft is delivered through SSE only; business operations consume diarized confirmed text.
            if (!text.isBlank() && turn.endMs()>confirmedUntil) stream.publishDraft(batchId,fileId,itemId,turn.startMs(),turn.endMs(),text);
            else stream.publishFinal(batchId,fileId,itemId);
            completed.add(itemId); finalized++;
            if (finishing && finalized == committed) drained.complete(null);
        }

        synchronized void finish() {
            if (finishing || closed) return;
            try { commit(); } catch (Exception e) { fail("尾部音频提交失败。"); return; }
            finishing = true;
            if (finalized == committed) drained.complete(null);
            CompletableFuture.allOf(drained.orTimeout(Math.max(15, properties.recording().readTimeoutSeconds()), TimeUnit.SECONDS), windows.finish())
                    .whenCompleteAsync((ignored, error) -> {
                        if (error != null) { fail("等待最终转写超时，请重新演示。"); return; }
                        synchronized (this) {
                            if (closed) return;
                            try { recordings.finishRealtime(fileId); cancel(); }
                            catch (Exception e) { fail("实时转写收尾失败。"); }
                        }
                    });
        }

        synchronized void fail(String detail) {
            if (closed) return;
            try { recordings.failRealtime(fileId, detail == null ? "实时转写失败。" : detail); }
            finally { cancel(); }
        }
        synchronized void cancel() {
            if (closed) return;
            closed = true; sessions.remove(id, this);
            capacity.release();
            if (connection != null) connection.close();
            windows.cancel();
            drained.completeExceptionally(new IllegalStateException("会话关闭。"));
        }
    }
}
