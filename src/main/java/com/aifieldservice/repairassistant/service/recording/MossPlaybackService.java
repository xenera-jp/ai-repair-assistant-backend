package com.aifieldservice.repairassistant.service.recording;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.aifieldservice.repairassistant.dao.recording.*;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.integration.moss.MossRecordingGateway;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;
import tools.jackson.databind.ObjectMapper;

/** Full-file transcription survives playback end/deletion; only played segments enter the conversation. */
@Service
public class MossPlaybackService {
    private final RecordingService recordings;
    private final RecordingMapper files;
    private final MossRecordingMapper mapper;
    private final MossRecordingGateway gateway;
    private final TransactionTemplate transactions;
    private final TransactionTemplate completionTransactions;
    private final ObjectMapper json=new ObjectMapper();
    private final Set<String> queued=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));

    public MossPlaybackService(RecordingService recordings,RecordingMapper files,MossRecordingMapper mapper,
            MossRecordingGateway gateway,PlatformTransactionManager manager) {
        this.recordings=recordings; this.files=files; this.mapper=mapper; this.gateway=gateway;
        transactions=new TransactionTemplate(manager);
        completionTransactions=new TransactionTemplate(manager);
        completionTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @jakarta.annotation.PreDestroy public void close() { workers.shutdown(); }
    static String cacheKey(String name) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public RecordingViews.Batch start(String fileKey) {
        var file=recordings.getFile(fileKey);
        String key=cacheKey(file.originalName());
        var job=mapper.job(key);
        if(job==null || Set.of("FAILED","PENDING").contains(job.status())) {
            try { gateway.validate(); }
            catch(IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,e.getMessage()); }
        }
        transactions.executeWithoutResult(tx -> {
            var locked=files.findFileIncludingDeletedForUpdate(fileKey);
            if(locked==null || locked.deleted()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"录音不存在。");
            if(mapper.playback(fileKey)!=null) return;
            mapper.insertJob(key,file.originalName(),fileKey);
            mapper.retry(key,fileKey);
            recordings.beginRealtime(fileKey);
            mapper.insertPlayback(fileKey,key);
        });
        enqueue(key); refresh(fileKey);
        return batch(fileKey);
    }
    public RecordingViews.Batch progress(String fileKey,long positionMs,boolean ended) {
        if(positionMs<0 || positionMs>24*60*60*1000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"播放时间无效。");
        recordings.getFile(fileKey);
        transactions.executeWithoutResult(tx -> {
            var playback=mapper.lockPlayback(fileKey);
            if(playback==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先开始播放。");
            if(!playback.ended()) mapper.progress(fileKey,ended?positionMs:Math.max(positionMs,playback.positionMs()),ended);
        });
        refresh(fileKey);
        return batch(fileKey);
    }
    private RecordingViews.Batch batch(String fileKey) {
        var file=recordings.getFile(fileKey);
        return recordings.getBatch(files.findBatchById(file.batchId()).batchKey());
    }
    private void enqueue(String key) {
        if(!"PENDING".equals(mapper.job(key).status()) || !queued.add(key)) return;
        try { workers.execute(() -> { try { transcribe(key); } finally { queued.remove(key); } }); }
        catch(RejectedExecutionException e) { queued.remove(key); throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"MOSS 任务繁忙，请稍后重试。"); }
    }
    private void transcribe(String key) {
        if(mapper.claim(key)!=1) return;
        var job=mapper.job(key);
        var result=new ArrayList<Transcript>();
        var speakers=new LinkedHashMap<String,String>();
        try {
            var source=files.findFileIncludingDeleted(job.sourceFileKey());
            if(source==null) throw new IllegalStateException("录音文件不存在。");
            gateway.transcribe(recordings.resolveContent(source),job.originalName(),segment -> {
                String speaker=segment.speaker();
                if(!speaker.equals("UNKNOWN")) speaker=speakers.computeIfAbsent(speaker,s -> speakers.size()==0?"A":speakers.size()==1?"B":"UNKNOWN");
                result.add(new Transcript(segment.providerId(),segment.startMs(),segment.endMs(),speaker,segment.text()));
                mapper.save(key,json.writeValueAsString(result),"RUNNING",null);
                refreshSubscribers(key);
            });
            mapper.save(key,json.writeValueAsString(result),"COMPLETED",null);
        } catch(Exception e) {
            mapper.save(key,json.writeValueAsString(result),"FAILED","MOSS 整文件转写失败，请重新上传后重试。");
        }
        refreshSubscribers(key);
    }
    private void refreshSubscribers(String key) {
        for(var playback:mapper.subscribers(key)) {
            try { refresh(playback.fileKey()); }
            catch(Exception ignored) { /* A deleted or disconnected playback never cancels the shared transcription. */ }
        }
    }
    void refresh(String fileKey) {
        transactions.executeWithoutResult(tx -> {
            var playback=mapper.lockPlayback(fileKey);
            if(playback==null || playback.finalized()) return;
            var file=files.findFileIncludingDeleted(fileKey);
            if(file==null || file.deleted()) return;
            var job=mapper.job(playback.jobKey());
            var visible=readSegments(job.resultJson()).stream().filter(s -> s.endMs()<=playback.positionMs()).toList();
            recordings.replaceConfirmed(fileKey,visible);
            if("FAILED".equals(job.status())) { recordings.failRealtime(fileKey,job.errorDetail()); return; }
            if(playback.ended() && "COMPLETED".equals(job.status()) && mapper.finalizePlayback(fileKey)==1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { completionTransactions.executeWithoutResult(status -> recordings.finishRealtime(fileKey)); }
                });
            }
        });
    }
    List<Transcript> readSegments(String value) {
        return Arrays.stream(json.readValue(value,Transcript[].class)).sorted(Comparator.comparingLong(Transcript::startMs)).toList();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        mapper.recoverJobs();
        for(var playback:mapper.recoverablePlaybacks()) {
            var file=files.findFileIncludingDeleted(playback.fileKey());
            mapper.recoverPlayback(playback.fileKey());
            files.updateFileStatus(file.id(),"REALTIME_TRANSCRIBING",null,null);
            files.updateBatchStatus(file.batchId(),"TRANSCRIBING",null);
            refresh(playback.fileKey());
        }
        mapper.pendingJobs().forEach(job -> enqueue(job.jobKey()));
    }
}
