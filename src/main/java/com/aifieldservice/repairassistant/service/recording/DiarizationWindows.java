package com.aifieldservice.repairassistant.service.recording;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import com.aifieldservice.repairassistant.dao.recording.DiarizationWindowMapper;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;
import tools.jackson.databind.ObjectMapper;

@Service
public class DiarizationWindows {
    private final OpenAiRecordingGateway gateway;
    private final DiarizationWindowMapper mapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    public DiarizationWindows(OpenAiRecordingGateway gateway, DiarizationWindowMapper mapper) { this.gateway = gateway; this.mapper = mapper; }
    @PreDestroy public void close() { executor.shutdownNow(); }
    public State create(String session, String file, String language, Consumer<List<Transcript>> save, Consumer<String> failure) {
        return new State(session, file, language, save, failure);
    }
    public final class State {
        private final String session, file, language;
        private final Consumer<List<Transcript>> save;
        private final Consumer<String> failure;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ArrayDeque<Window> queue = new ArrayDeque<>();
        private final Map<String, String> references = new LinkedHashMap<>();
        private final DiarizationMerger merger = new DiarizationMerger();
        private CompletableFuture<Void> drained = new CompletableFuture<>();
        private long startSample, submittedEnd;
        private boolean working, ending, cancelled, failed;
        private int sequence;
        private record Window(String id, long start, byte[] pcm, int sequence) {}
        State(String session, String file, String language, Consumer<List<Transcript>> save, Consumer<String> failure) {
            this.session=session; this.file=file; this.language=language; this.save=save; this.failure=failure;
        }
        public synchronized boolean full() { return queue.size() >= 3 || failed; }
        public synchronized void append(byte[] pcm, boolean silence) {
            if (cancelled || ending) throw new IllegalStateException("分离输入已结束。");
            buffer.writeBytes(pcm);
            int size = buffer.size();
            if (size >= 12*48000 && silence || size >= 15*48000) submit(false);
        }
        private void submit(boolean tail) {
            byte[] pcm = buffer.toByteArray();
            long end = startSample + pcm.length / 2;
            if (end <= submittedEnd) return;
            String id = session + "_" + sequence;
            mapper.insert(id, session, file, startSample*1000/24000, end*1000/24000,
                    Math.max(0, submittedEnd-startSample)*1000/24000);
            queue.add(new Window(id, startSample, pcm, sequence++)); submittedEnd=end;
            buffer.reset();
            if (!tail) {
                int keep = Math.min(3*48000, pcm.length);
                buffer.write(pcm, pcm.length-keep, keep); startSample=end-keep/2;
            }
            launch();
        }
        private void launch() {
            if (working || failed || cancelled || queue.isEmpty()) return;
            working=true; executor.execute(() -> {
                try { process(); }
                catch(Exception e) {
                    synchronized(this) {
                        working=false; if(cancelled) return; failed=true;
                        failure.accept("分离窗口状态保存失败，请重试分离或重头演示。");
                    }
                }
            });
        }
        private void process() {
            Window window;
            synchronized(this) { window=queue.peek(); }
            List<Transcript> segments=null;
            int attemptsUsed=0;
            for (int attempt=1; attempt<=3; attempt++) {
                synchronized(this) { if(cancelled) { working=false; return; } }
                try {
                    mapper.update(window.id(), "PROCESSING", attempt, null, null);
                    segments=gateway.diarize(wav(window.pcm()), language, Map.copyOf(references));
                    attemptsUsed=attempt;
                    mapper.update(window.id(), "DIARIZED", attempt, new ObjectMapper().writeValueAsString(segments), null);
                    break;
                } catch (Exception e) {
                    mapper.update(window.id(), "FAILED", attempt, null, "说话人分离请求失败。");
                }
            }
            synchronized(this) {
                working=false; if(cancelled) return;
                if(segments==null) {
                    failed=true; failure.accept("说话人分离失败，未确认范围：" + window.start()*1000/24000 + "ms 起。请重试分离或重头演示。"); return;
                }
                try {
                    var confirmed=merger.merge(window.start()*1000/24000, segments, references.keySet());
                    save.accept(confirmed);
                    long offset=window.start()*1000/24000;
                    long end=offset+window.pcm().length*1000L/48000;
                    var associated=confirmed.stream().filter(s -> s.endMs()>offset && s.startMs()<end).toList();
                    mapper.update(window.id(),"COMPLETED",attemptsUsed,new ObjectMapper().writeValueAsString(Map.of(
                            "localSegments",segments,"confirmedSegments",associated,"referenceNames",List.copyOf(references.keySet()))),null);
                    referenceClips(offset,window.pcm(),confirmed).forEach(references::putIfAbsent);
                    queue.remove(); launch();
                    if(ending && queue.isEmpty() && !working) drained.complete(null);
                } catch(Exception e) {
                    mapper.update(window.id(),"FAILED",attemptsUsed,null,"说话人结果关联或保存失败。");
                    failed=true; failure.accept("说话人结果关联或保存失败，请重试分离。");
                }
            }
        }
        public synchronized CompletableFuture<Void> finish() {
            if(!ending) { ending=true; submit(true); }
            if(queue.isEmpty() && !working) drained.complete(null);
            return drained;
        }
        public synchronized void retry() { if(failed) { failed=false; launch(); } }
        public synchronized void cancel() { cancelled=true; queue.clear(); buffer.reset(); drained.completeExceptionally(new IllegalStateException("分离已取消。")); }
    }
    /** Reference intervals come from model-labelled source audio, including contiguous short segments. */
    static Map<String,String> referenceClips(long offset,byte[] pcm,List<Transcript> confirmed) {
        long windowEnd=offset+pcm.length*1000L/48000;
        var candidates=confirmed.stream().filter(s -> Set.of("A","B").contains(s.speaker())
                && s.startMs()>=offset && s.endMs()<=windowEnd && s.endMs()>s.startMs())
                .sorted(Comparator.comparingLong(Transcript::startMs)).toList();
        var result=new LinkedHashMap<String,String>();
        for(int i=0;i<candidates.size();i++) {
            Transcript first=candidates.get(i);
            String name="session_"+first.speaker(); if(result.containsKey(name)) continue;
            long start=first.startMs(), end=first.endMs();
            for(int j=i+1;j<candidates.size();j++) {
                Transcript next=candidates.get(j);
                if(!next.speaker().equals(first.speaker()) || next.startMs()>end+1000) break;
                end=Math.max(end,next.endMs());
                if(end-start>=10000) break;
            }
            end=Math.min(end,start+10000);
            if(end-start<2000) continue;
            final long clipStart=start,clipEnd=end;
            boolean mixed=confirmed.stream().anyMatch(other -> !other.speaker().equals(first.speaker())
                    && other.startMs()<clipEnd && other.endMs()>clipStart);
            if(mixed) continue;
            int from=(int)((start-offset)*48),to=(int)((end-offset)*48);
            if(from>=0 && to<=pcm.length) result.put(name,"data:audio/wav;base64,"+
                    Base64.getEncoder().encodeToString(wav(Arrays.copyOfRange(pcm,from,to))));
        }
        return result;
    }
    static byte[] wav(byte[] pcm) {
        var b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+pcm.length)
         .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1)
         .putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
         .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
