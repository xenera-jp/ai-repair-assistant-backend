package com.aifieldservice.repairassistant.service.recording.impl;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.dao.recording.RecordingMapper;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import com.aifieldservice.repairassistant.service.recording.RecordingService;
import com.aifieldservice.repairassistant.service.recording.RecordingTranscriptionStream;
import com.aifieldservice.repairassistant.service.recording.EquipmentIdentifierMatchingService;
import com.aifieldservice.repairassistant.service.recording.RecordingService.CorrectionDecision;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

@Service
public class RecordingServiceImpl implements RecordingService {
    private static final Set<String> EXTENSIONS = Set.of("flac","mp3","mp4","mpeg","mpga","m4a","ogg","wav","webm");
    private static final Set<String> ROLES = Set.of("CUSTOMER_SERVICE","CUSTOMER","FIELD_ENGINEER","OTHER","UNKNOWN");
    private static final List<String> TYPE_ORDER = List.of("MODEL","SYMPTOM","ERROR_CODE","OPERATING_STATUS","OCCURRENCE","MEASUREMENT","ENVIRONMENT","RECENT_CHANGES","PHOTO_EVIDENCE");

    private final RecordingMapper mapper;
    private final RepairAssistantProperties properties;
    private final OpenAiRecordingGateway openAi;
    private final EquipmentIdentifierMatchingService identifierMatching;
    private final RecordingTranscriptionStream transcriptionStream;
    private final TransactionTemplate transactions;
    private final Path root;
    private final ExecutorService workers;
    private final ObjectMapper json = new ObjectMapper();

    public RecordingServiceImpl(RecordingMapper mapper, RepairAssistantProperties properties, OpenAiRecordingGateway openAi,
            EquipmentIdentifierMatchingService identifierMatching, RecordingTranscriptionStream transcriptionStream,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.properties = properties; this.openAi = openAi;
        this.identifierMatching = identifierMatching;
        this.transcriptionStream = transcriptionStream;
        this.transactions = new TransactionTemplate(transactionManager);
        this.root = Path.of(properties.recording().storagePath()).toAbsolutePath().normalize();
        try { Files.createDirectories(root.resolve(".uploading")); }
        catch (Exception e) { throw new IllegalStateException("无法创建录音存储目录: " + root, e); }
        workers = Executors.newFixedThreadPool(Math.max(1, properties.recording().workerConcurrency()),
                Thread.ofPlatform().name("recording-worker-", 0).factory());
    }

    @PreDestroy public void close() { workers.shutdown(); }

    /** Resume jobs left incomplete by a process restart instead of leaving the UI polling forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeIncompleteTranscriptions() {
        mapper.listIncompleteFiles().forEach(file -> {
            mapper.updateFileStatus(file.id(), "FAILED", "REALTIME_INTERRUPTED", "会话已中断，请重头演示。");
            mapper.updateBatchStatus(file.batchId(), "FAILED", "会话已中断，请重头演示。");
        });
    }

    @Override @Transactional
    public RecordingViews.Batch create(List<MultipartFile> uploads, String language) {
        return createUpload(uploads, language);
    }

    @Override @Transactional
    public RecordingViews.Batch createRealtime(List<MultipartFile> uploads, String language) {
        return createUpload(uploads, language);
    }

    private RecordingViews.Batch createUpload(List<MultipartFile> uploads, String language) {
        if (uploads == null || uploads.isEmpty()) throw bad("请至少选择一个录音文件。");
        if (uploads.size() != 1) throw bad("一次只能上传一个录音文件。");
        String normalizedLanguage = Set.of("zh-CN","ja-JP","AUTO").contains(language) ? language : "AUTO";
        String batchKey = key("rb");
        mapper.insertBatch(batchKey, normalizedLanguage, "UPLOADING");
        RecordingRows.Batch batch = requiredBatch(batchKey);
        List<String> fileKeys = new ArrayList<>();
        try {
            for (int i=0; i<uploads.size(); i++) {
                MultipartFile upload = uploads.get(i);
                validate(upload);
                String extension = extension(upload.getOriginalFilename());
                String fileKey = key("rf");
                String storageKey = batchKey + "/" + fileKey + "/source." + extension;
                Path target = safePath(storageKey);
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(root.resolve(".uploading"), fileKey + "-", ".tmp");
                try (InputStream input = upload.getInputStream()) { Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING); }
                String sha = sha256(temporary);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                mapper.insertFile(fileKey, batch.id(), i, safeName(upload.getOriginalFilename()), storageKey,
                        upload.getContentType() == null ? "application/octet-stream" : upload.getContentType(), upload.getSize(), sha, "PREPARED");
                mapper.markRealtime(requiredFile(fileKey).id());
                fileKeys.add(fileKey);
            }
            mapper.updateBatchStatus(batch.id(), "PREPARED", null);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "录音保存失败。", e); }
        return getBatch(batchKey);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("录音文件不能为空。");
        if (file.getSize() > properties.recording().maxFileSizeBytes()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "录音文件超过大小限制。");
        if (!EXTENSIONS.contains(extension(file.getOriginalFilename()))) throw bad("不支持的录音格式: " + safeName(file.getOriginalFilename()));
    }

    private void publishBatch(String batchKey) {
        try { transcriptionStream.publishBatch(batchKey, view(requiredBatch(batchKey))); }
        catch (Exception ignored) { /* Browser delivery must not fail the persisted background job. */ }
    }

    private void inferRoles(long fileId, String language) {
        List<RecordingRows.Segment> segments = mapper.listSegments(fileId);
        if (mapper.isRealtime(fileId)) segments = segments.stream().filter(s -> Set.of("A", "B").contains(s.speakerLabel())).toList();
        Set<String> valid = new HashSet<>(); segments.forEach(s -> valid.add(s.speakerLabel()));
        try {
            for (var role : openAi.inferRoles(segments, language)) {
                if (!valid.contains(role.speakerLabel()) || !ROLES.contains(role.roleCode())) continue;
                String shown = role.confidence() >= properties.recording().speakerRoleConfidenceThreshold() ? role.roleCode() : "UNKNOWN";
                mapper.updateSpeakerRole(fileId, role.speakerLabel(), shown, role.confidence(), "MODEL");
            }
        } catch (Exception ignored) { /* role inference is optional */ }
    }

    private synchronized void refreshBatch(long batchId) {
        RecordingRows.Batch batch = batchById(batchId);
        List<RecordingRows.File> files = mapper.listFiles(batchId);
        if (files.stream().anyMatch(f -> Set.of("PREPARED", "TRANSCRIBING", "UPLOADED", "REALTIME_TRANSCRIBING", "ROLE_INFERENCE", "DIARIZATION_FAILED").contains(f.status()))) return;
        long successes = files.stream().filter(f -> "COMPLETED".equals(f.status())).count();
        if (successes == 0) { mapper.updateBatchStatus(batchId, "FAILED", "全部文件转写失败。"); return; }
        mapper.updateBatchStatus(batchId, successes == files.size() ? "TRANSCRIBED" : "PARTIAL_SUCCESS", null);
    }

    private void extract(String batchKey) {
        RecordingRows.Batch batch = requiredBatch(batchKey);
        List<RecordingRows.Segment> segments = mapper.listBatchSegments(batch.id());
        if (segments.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可用于提取的转写原文。");
        try {
            List<OpenAiRecordingGateway.Extracted> original = openAi.extract(segments, batch.languageCode());
            var match = identifierMatching.match(original, segments);
            List<OpenAiRecordingGateway.Extracted> extracted = match.items();
            mapper.incrementExtractionRevision(batch.id());
            int revision = requiredBatch(batchKey).extractionRevision();
            Map<String,RecordingRows.Segment> byKey = new HashMap<>(); segments.forEach(s -> byKey.put(s.segmentKey(), s));
            int order=0;
            for (int itemIndex=0; itemIndex<extracted.size(); itemIndex++) {
                var item = extracted.get(itemIndex);
                String content = item.content() == null ? "" : item.content().strip();
                if (!TYPE_ORDER.contains(item.type()) || content.isBlank() || content.length() > 1000) continue;
                List<RecordingRows.Segment> evidence = item.evidenceSegmentIds().stream().distinct().map(byKey::get).filter(java.util.Objects::nonNull).toList();
                if (evidence.isEmpty()) continue;
                String issueKey = key("ri");
                String originalContent=original.get(itemIndex).content()==null?content:original.get(itemIndex).content().strip();
                mapper.insertIssue(issueKey, batch.id(), revision, item.type(), content, originalContent, order++);
                RecordingRows.Issue issue = mapper.findIssue(batch.id(), issueKey);
                for (int i=0; i<evidence.size(); i++) mapper.insertEvidence(issue.id(), evidence.get(i).id(), i);
                int decisionIndex=itemIndex;
                match.decisions().stream().filter(d->d.itemIndex()==decisionIndex).findFirst().ifPresent(d->mapper.insertCorrection(
                        key("rc"),batch.id(),revision,issue.id(),item.type(),d.originalValue(),d.suggestedValue(),d.model(),
                        d.sourceText(),json.writeValueAsString(item.evidenceSegmentIds()),json.writeValueAsString(d.candidates()),
                        d.score(),d.margin(),d.reason(),d.status()));
            }
            if (order == 0) mapper.updateBatchStatus(batch.id(), "EXTRACTION_FAILED", "未提取到有原文证据的设备问题。");
            else mapper.updateBatchStatus(batch.id(), mapper.countPendingCorrections(batch.id(),revision)>0?"REVIEW_REQUIRED":"READY", null);
        } catch (Exception e) { mapper.updateBatchStatus(batch.id(), "EXTRACTION_FAILED", concise(e)); }
    }

    @Override @Transactional public RecordingViews.Batch getBatch(String batchId) { RecordingRows.Batch batch=requiredBatch(batchId);reconcileAcceptedErrorCodes(batch);return view(requiredBatch(batchId)); }

    @Override @Transactional public RecordingViews.Batch setSegmentSpeaker(String fileId, String segmentId, String speaker) {
        if (speaker == null || !Set.of("A", "B", "UNKNOWN", "MIXED").contains(speaker)) throw bad("无效的说话人标签。");
        var file = mapper.findFileIncludingDeletedForUpdate(fileId);
        if (file == null || file.deleted()) throw notFound("录音不存在。");
        var batch = mapper.findBatchByIdForUpdate(file.batchId());
        if (batch == null || batch.deleted()) throw notFound("录音不存在。");
        if (!mapper.isRealtime(file.id()) || !"COMPLETED".equals(file.status())
                || !Set.of("TRANSCRIBED", "PARTIAL_SUCCESS", "EXTRACTION_FAILED").contains(batch.status()) || batch.extractionRevision() != 0)
            throw conflict("请在转写和角色识别完成后、对话总结之前修正说话人。");
        String role = Set.of("A", "B").contains(speaker) ? mapper.listSegments(file.id()).stream()
                .filter(s -> speaker.equals(s.speakerLabel()) && s.speakerRoleCode() != null)
                .map(RecordingRows.Segment::speakerRoleCode).findFirst().orElse("UNKNOWN") : "UNKNOWN";
        if (mapper.updateSegmentSpeaker(file.id(), segmentId, speaker, role) != 1) throw notFound("转写片段不存在。");
        return getBatch(batch.batchKey());
    }
    @Override @Transactional public RecordingViews.Batch setSegmentSpeakers(String fileId,List<String> segmentIds,String speaker) {
        if(segmentIds==null || segmentIds.isEmpty() || segmentIds.size()>10000) throw bad("请选择有效的转写片段。");
        RecordingViews.Batch result=null;
        for(String id:segmentIds.stream().distinct().toList()) result=setSegmentSpeaker(fileId,id,speaker);
        return result;
    }
    @Override public RecordingViews.Batch retryTranscription(String fileId) {
        requiredFile(fileId);
        throw conflict("请通过重头演示重试，不再使用整文件转写。");
    }
    @Override public RecordingViews.Batch retryExtraction(String batchId) {
        RecordingRows.Batch batch = requiredBatch(batchId);
        if (mapper.listBatchSegments(batch.id()).isEmpty()) throw conflict("没有可用于提取的转写原文。");
        if (mapper.claimExtraction(batch.id()) != 1) throw conflict("请等待转写和角色识别完成，或当前提取已经开始。");
        Runnable task=() -> workers.execute(() -> { extract(batchId); publishBatch(batchId); });
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { task.run(); }
            });
        } else task.run();
        return getBatch(batchId);
    }
    @Override @Transactional public RecordingViews.Batch retryExtraction(String batchId,String conversationVersion) {
        var batch=requiredBatch(batchId);
        mapper.findBatchByIdForUpdate(batch.id());
        if(conversationVersion==null || !view(requiredBatch(batchId)).conversationVersion().equals(conversationVersion))
            throw conflict("对话版本已更新，请刷新后重新点击对话总结。");
        return retryExtraction(batchId);
    }

    @Override @Transactional public RecordingViews.Batch beginRealtime(String fileId) {
        var file = mapper.findFileIncludingDeletedForUpdate(fileId);
        if (file == null || file.deleted()) throw notFound("录音不存在。");
        if (!mapper.isRealtime(file.id()) || !"PREPARED".equals(file.status())) throw conflict("实时会话已开始，请重新上传以重头演示。");
        mapper.softDeleteSegments(file.id(), "REALTIME_RESTART");
        mapper.updateFileStatus(file.id(), "REALTIME_TRANSCRIBING", null, null);
        mapper.updateBatchStatus(file.batchId(), "TRANSCRIBING", null);
        return getBatch(batchById(file.batchId()).batchKey());
    }

    @Override @Transactional public void saveRealtimeSegment(String fileId, String itemId, int sequence,
            long startMs, long endMs, String speaker, String text) {
        var file = mapper.findFileIncludingDeletedForUpdate(fileId);
        if (file == null) return;
        boolean removed = file.deleted();
        if (!removed && !"REALTIME_TRANSCRIBING".equals(file.status())) return;
        mapper.insertSegment(key("rs"), file.id(), sequence, speaker, startMs, endMs, text, itemId, removed);
        if (!removed) publishBatch(batchById(file.batchId()).batchKey());
    }

    @Override public void finishRealtime(String fileId) {
        var file = requiredFile(fileId);
        mapper.updateFileStatus(file.id(), "ROLE_INFERENCE", null, null);
        mapper.updateBatchStatus(file.batchId(), "ROLE_INFERENCE", null);
        publishBatch(batchById(file.batchId()).batchKey());
        workers.execute(() -> {
            inferRoles(file.id(), batchById(file.batchId()).languageCode());
            transactions.executeWithoutResult(status -> {
                var current = mapper.findFileIncludingDeletedForUpdate(fileId);
                if (current == null || current.deleted()) return;
                boolean empty = mapper.listSegments(file.id()).isEmpty();
                mapper.updateFileStatus(file.id(), empty ? "FAILED" : "COMPLETED", empty ? "EMPTY_TRANSCRIPT" : null,
                        empty ? "没有识别到有效文字。" : null);
                refreshBatch(file.batchId());
            });
            publishBatch(batchById(file.batchId()).batchKey());
        });
    }

    @Override @Transactional public void replaceConfirmed(String fileId, List<OpenAiRecordingGateway.Transcript> segments) {
        var file=mapper.findFileIncludingDeletedForUpdate(fileId);
        if(file==null || file.deleted() || !"REALTIME_TRANSCRIBING".equals(file.status())) return;
        var existing=new ArrayList<>(mapper.listSegments(file.id()));
        int sequence=0;
        for(var s:segments) {
            var same=existing.stream().filter(old -> old.startMs()==s.startMs() && old.endMs()==s.endMs()
                    && old.speakerLabel().equals(s.speaker()) && old.originalText().equals(s.text())).findFirst();
            if(same.isPresent()) {
                var old=same.get(); if(old.sequenceNo()!=sequence) mapper.orderSegment(old.id(),sequence);
                existing.remove(old);
            } else mapper.insertSegment(key("rs"),file.id(),sequence,s.speaker(),s.startMs(),s.endMs(),s.text(),s.providerId(),false);
            sequence++;
        }
        existing.forEach(old -> mapper.retireSegment(old.id()));
        publishBatch(batchById(file.batchId()).batchKey());
    }
    @Override @Transactional public void diarizationFailed(String fileId,String detail) {
        var file=requiredFile(fileId);
        if(!"REALTIME_TRANSCRIBING".equals(file.status())) return;
        mapper.updateFileStatus(file.id(),"DIARIZATION_FAILED","DIARIZATION_FAILED",detail);
        mapper.updateBatchStatus(file.batchId(),"DIARIZATION_FAILED",detail);
        publishBatch(batchById(file.batchId()).batchKey());
    }
    @Override @Transactional public void resumeDiarization(String fileId) {
        var file=requiredFile(fileId);
        if(!"DIARIZATION_FAILED".equals(file.status())) throw conflict("没有待重试的分离窗口。");
        mapper.updateFileStatus(file.id(),"REALTIME_TRANSCRIBING",null,null);
        mapper.updateBatchStatus(file.batchId(),"TRANSCRIBING",null);
        publishBatch(batchById(file.batchId()).batchKey());
    }

    @Override @Transactional public void failRealtime(String fileId, String detail) {
        var file = mapper.findFileIncludingDeletedForUpdate(fileId);
        if (file == null || file.deleted() || !Set.of("REALTIME_TRANSCRIBING", "ROLE_INFERENCE", "DIARIZATION_FAILED").contains(file.status())) return;
        String safeDetail = detail == null ? "实时会话已中断。" : detail.substring(0, Math.min(1000, detail.length()));
        mapper.updateFileStatus(file.id(), "FAILED", "REALTIME_INTERRUPTED", safeDetail);
        mapper.updateBatchStatus(file.batchId(), "FAILED", safeDetail);
        publishBatch(batchById(file.batchId()).batchKey());
    }
    @Override @Transactional public RecordingViews.Batch setSpeakerRole(String fileId,String speakerLabel,String roleCode) {
        if (!ROLES.contains(roleCode)) throw bad("无效的说话人角色。");
        RecordingRows.File file=mapper.findFileIncludingDeletedForUpdate(fileId);
        if(file==null || file.deleted()) throw notFound("录音不存在。");
        var batch=mapper.findBatchByIdForUpdate(file.batchId());
        if(batch==null || batch.deleted()) throw notFound("录音不存在。");
        if(!"COMPLETED".equals(file.status()) || !Set.of("TRANSCRIBED","PARTIAL_SUCCESS","EXTRACTION_FAILED").contains(batch.status())
                || batch.extractionRevision()!=0) throw conflict("请在处理完成后、对话总结前修改角色。");
        if (mapper.updateSpeakerRole(file.id(),speakerLabel,roleCode,1.0,"MANUAL")==0) throw notFound("说话人不存在。");
        return view(batchById(file.batchId()));
    }
    @Override public RecordingViews.Batch updateIssue(String batchId,String issueId,String content,int version) {
        RecordingRows.Batch batch=requiredBatch(batchId); RecordingRows.Issue issue=requiredIssue(batch,issueId);
        String value=content==null?"":content.strip(); if(value.isBlank()||value.length()>1000) throw bad("提取内容长度必须为 1-1000 个字符。");
        if(mapper.updateIssueContent(issue.id(),value,version)==0) throw conflict("内容已被其他操作修改，请刷新后重试。");
        mapper.invalidatePendingCorrection(issue.id());
        if("REVIEW_REQUIRED".equals(batch.status())&&mapper.countPendingCorrections(batch.id(),batch.extractionRevision())==0) mapper.updateBatchStatus(batch.id(),"READY",null);
        return view(requiredBatch(batchId));
    }

    @Override public RecordingViews.Batch createIssue(String batchId,String type,String content) {
        RecordingRows.Batch batch=requiredBatch(batchId);
        String normalizedType="OTHER".equals(type)?"RECENT_CHANGES":type;
        if(!TYPE_ORDER.contains(normalizedType)) throw bad("不支持的关键信息类型。");
        String value=content==null?"":content.strip();
        if(value.isBlank()) throw bad("内容不能为空。");
        mapper.insertIssue(key("ri"),batch.id(),batch.extractionRevision(),normalizedType,value,value,TYPE_ORDER.indexOf(normalizedType)+1);
        return view(requiredBatch(batchId));
    }
    @Override public RecordingViews.Batch setIssueDeleted(String batchId,String issueId,boolean deleted) {
        RecordingRows.Batch batch=requiredBatch(batchId); RecordingRows.Issue issue=requiredIssue(batch,issueId); mapper.updateIssueDeleted(issue.id(),deleted); return view(requiredBatch(batchId));
    }
    @Override @Transactional public RecordingViews.Batch confirmCorrections(String batchId,List<CorrectionDecision> decisions) {
        RecordingRows.Batch batch=requiredBatch(batchId);
        if(!"REVIEW_REQUIRED".equals(batch.status())) throw conflict("当前批次没有待确认的标识校正。");
        if(decisions==null||decisions.isEmpty()) throw bad("请提交至少一个确认决定。");
        for(var decision:decisions) {
            if(!Set.of("ACCEPT","KEEP_ORIGINAL","MANUAL_VALUE").contains(decision.decision())) throw bad("无效的校正确认决定。");
            RecordingRows.Issue issue=requiredIssue(batch,decision.issueId());
            if(issue.revision()!=batch.extractionRevision()||issue.deleted()||issue.versionNo()!=decision.version()) throw conflict("提取项已变化，请刷新后重新确认。");
            RecordingRows.Correction correction=mapper.findCorrectionForIssue(issue.id());
            if(correction==null||!Set.of("LLM_PENDING_CONFIRMATION","MANUAL_INPUT_REQUIRED").contains(correction.status())) throw conflict("校正建议已变化，请刷新后重试。");
            if("ACCEPT".equals(decision.decision())&&!"LLM_PENDING_CONFIRMATION".equals(correction.status())) throw bad("该项没有可采用的 AI 建议，请手动填写标准值。");
            String target=null;
            if("ACCEPT".equals(decision.decision())) target=correction.suggestedValue();
            if("MANUAL_VALUE".equals(decision.decision())) {
                target=decision.value()==null?"":decision.value().strip();
                if(target.isBlank()||target.length()>1000) throw bad("手动填写内容长度必须为 1-1000 个字符。");
            }
            if(target!=null&&mapper.applyCorrectionContent(issue.id(),target,decision.version())==0) throw conflict("提取项已变化，请刷新后重新确认。");
            if(target!=null&&"ERROR_CODE".equals(issue.issueType())) syncAcceptedErrorCode(batch,issue,correction,target);
            String resolvedStatus="KEEP_ORIGINAL".equals(decision.decision())?"USER_REJECTED_LLM":("MANUAL_VALUE".equals(decision.decision())?"MANUAL_EDIT":"USER_ACCEPTED_LLM");
            if(mapper.resolveCorrection(correction.id(),resolvedStatus)==0) throw conflict("校正建议已被处理。");
        }
        if(mapper.countPendingCorrections(batch.id(),batch.extractionRevision())==0) mapper.updateBatchStatus(batch.id(),"READY",null);
        return view(requiredBatch(batchId));
    }
    @Override public RecordingRows.File getFile(String id){return requiredFile(id);}
    @Override public Path resolveContent(RecordingRows.File file){Path p=safePath(file.storageKey()); if(!Files.isRegularFile(p)) throw notFound("录音文件不存在。"); return p;}
    @Override @Transactional public void deleteFile(String id){
        var f=mapper.findFileIncludingDeletedForUpdate(id);
        if(f==null||f.deleted())return;
        String reason="USER_DELETED";
        mapper.softDeleteFile(f.id(),reason);
        mapper.softDeleteSegments(f.id(),reason);
        mapper.softDeleteIssues(f.batchId(),reason);
        mapper.softDeleteEvidence(f.batchId(),reason);
        mapper.softDeleteCorrections(f.batchId(),reason);
        mapper.softDeleteBatch(f.batchId(),reason);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){transcriptionStream.publishDeleted(batchById(f.batchId()).batchKey(),f.fileKey());}
        });
    }

    private RecordingViews.Batch view(RecordingRows.Batch batch) {
        List<RecordingRows.File> files=mapper.listFiles(batch.id());
        List<RecordingRows.Issue> issues=batch.extractionRevision()>0?mapper.listIssues(batch.id(),batch.extractionRevision()):List.of();
        Map<Long,RecordingRows.Correction> corrections=new HashMap<>();
        if(batch.extractionRevision()>0) mapper.listCorrections(batch.id(),batch.extractionRevision()).forEach(c->corrections.put(c.issueId(),c));
        Map<String,List<RecordingViews.Evidence>> evidence=new HashMap<>();
        if(batch.extractionRevision()>0) for(var e:mapper.listEvidence(batch.id(),batch.extractionRevision())) evidence.computeIfAbsent(e.issueKey(),k->new ArrayList<>()).add(new RecordingViews.Evidence(e.fileKey(),e.originalName(),e.segmentKey(),e.speakerLabel(),e.speakerRoleCode(),e.startMs(),e.endMs(),e.originalText()));
        return new RecordingViews.Batch(batch.batchKey(),batch.languageCode(),batch.status(),batch.extractionRevision(),batch.extractionError(),
            files.stream().map(f->new RecordingViews.File(f.fileKey(),f.originalName(),f.contentType(),f.sizeBytes(),f.status(),f.errorCode(),f.errorDetail(),mapper.listSegments(f.id()).stream().map(s->new RecordingViews.Segment(s.segmentKey(),s.sequenceNo(),s.speakerLabel(),s.speakerRoleCode(),s.speakerRoleConfidence(),s.speakerRoleSource(),s.startMs(),s.endMs(),s.originalText())).toList(),mapper.isRealtime(f.id()))).toList(),
            issues.stream().map(i->new RecordingViews.Issue(i.issueKey(),i.issueType(),i.content(),i.originalContent(),i.editedByUser(),i.deleted(),i.versionNo(),evidence.getOrDefault(i.issueKey(),List.of()),correctionView(corrections.get(i.id())))).toList(),batch.createdAt());
    }
    private RecordingViews.Correction correctionView(RecordingRows.Correction c){if(c==null)return null;List<String> ids=new ArrayList<>();try{json.readTree(c.evidenceSegmentIdsJson()).forEach(n->ids.add(n.asText()));}catch(Exception ignored){}String source=EquipmentIdentifierMatchingService.identifierSource(new OpenAiRecordingGateway.Extracted(c.fieldType(),c.sourceText()==null?c.originalValue():c.sourceText(),ids));return new RecordingViews.Correction(c.status(),c.originalValue(),c.suggestedValue(),c.modelValue(),source,c.ruleScore(),c.reason(),ids);}
    private void syncAcceptedErrorCode(RecordingRows.Batch batch,RecordingRows.Issue codeIssue,RecordingRows.Correction correction){syncAcceptedErrorCode(batch,codeIssue,correction,correction.suggestedValue());}
    private void syncAcceptedErrorCode(RecordingRows.Batch batch,RecordingRows.Issue codeIssue,RecordingRows.Correction correction,String target){
        String source=correction.sourceText();if(source==null||source.isBlank()||target==null||target.isBlank()||source.equals(target))return;
        List<RecordingRows.Evidence> allEvidence=mapper.listEvidence(batch.id(),batch.extractionRevision());
        Set<String> codeFiles=allEvidence.stream().filter(e->e.issueKey().equals(codeIssue.issueKey())).map(RecordingRows.Evidence::fileKey).collect(java.util.stream.Collectors.toSet());
        if(codeFiles.isEmpty())return;
        Map<String,List<RecordingRows.Evidence>> evidenceByIssue=allEvidence.stream().collect(java.util.stream.Collectors.groupingBy(RecordingRows.Evidence::issueKey));
        for(var symptom:mapper.listIssues(batch.id(),batch.extractionRevision())){
            if(!"SYMPTOM".equals(symptom.issueType())||symptom.deleted()||symptom.editedByUser())continue;
            boolean sameFile=evidenceByIssue.getOrDefault(symptom.issueKey(),List.of()).stream().anyMatch(e->codeFiles.contains(e.fileKey()));if(!sameFile)continue;
            int start=symptom.content().indexOf(source);if(start<0||symptom.content().indexOf(source,start+source.length())>=0)continue;
            String after=symptom.content().substring(start+source.length());if(after.startsWith("℃")||after.startsWith("°C"))continue;
            String updated=symptom.content().substring(0,start)+target+after;
            if(mapper.applyCorrectionContent(symptom.id(),updated,symptom.versionNo())==0)throw conflict("症状内容已变化，请刷新后重新确认。");
            List<String> segmentIds=evidenceByIssue.getOrDefault(symptom.issueKey(),List.of()).stream().map(RecordingRows.Evidence::segmentKey).toList();
            if(mapper.findCorrectionForIssue(symptom.id())==null)mapper.insertCorrection(key("rc"),batch.id(),batch.extractionRevision(),symptom.id(),"SYMPTOM",symptom.content(),updated,correction.modelValue(),source,json.writeValueAsString(segmentIds),"[]",correction.ruleScore(),correction.scoreMargin(),"采用错误码建议后同步替换同一录音中的唯一旧写法。","RULE_AUTO_CORRECTED");
        }
    }
    private void reconcileAcceptedErrorCodes(RecordingRows.Batch batch){
        if(batch.extractionRevision()<=0)return;
        Map<Long,RecordingRows.Issue> issues=mapper.listIssues(batch.id(),batch.extractionRevision()).stream().collect(java.util.stream.Collectors.toMap(RecordingRows.Issue::id,i->i));
        for(var correction:mapper.listCorrections(batch.id(),batch.extractionRevision())){
            RecordingRows.Issue issue=issues.get(correction.issueId());
            if(issue!=null&&"ERROR_CODE".equals(issue.issueType())&&"USER_ACCEPTED_LLM".equals(correction.status()))syncAcceptedErrorCode(batch,issue,correction);
        }
    }
    private RecordingRows.Batch requiredBatch(String key){var b=mapper.findBatch(key);if(b==null)throw notFound("录音批次不存在。");return b;}
    private RecordingRows.Batch batchById(long id){var b=mapper.findBatchById(id);if(b==null)throw notFound("录音批次不存在。");return b;}
    private RecordingRows.File requiredFile(String key){var f=mapper.findFile(key);if(f==null)throw notFound("录音文件不存在。");return f;}
    private RecordingRows.Issue requiredIssue(RecordingRows.Batch b,String key){var i=mapper.findIssue(b.id(),key);if(i==null)throw notFound("提取项不存在。");return i;}
    private Path safePath(String storageKey){Path path=root.resolve(storageKey).normalize();if(!path.startsWith(root))throw bad("非法存储路径。");return path;}
    private String extension(String name){String value=name==null?"":name;int dot=value.lastIndexOf('.');return dot<0?"":value.substring(dot+1).toLowerCase(Locale.ROOT);}
    private String safeName(String name){String value=name==null?"recording":""+name;value=value.replace('\\','_').replace('/','_').replaceAll("[\\p{Cntrl}]","_");return value.length()>512?value.substring(value.length()-512):value;}
    private String sha256(Path path)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}return java.util.HexFormat.of().formatHex(digest.digest());}
    private String key(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    private String concise(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(900,m.length()));}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,m);} private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);} private ResponseStatusException notFound(String m){return new ResponseStatusException(HttpStatus.NOT_FOUND,m);}
}
