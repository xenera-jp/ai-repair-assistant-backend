package com.aifieldservice.repairassistant.service.recording.impl;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final Path root;
    private final ExecutorService workers;
    private final ObjectMapper json = new ObjectMapper();

    public RecordingServiceImpl(RecordingMapper mapper, RepairAssistantProperties properties, OpenAiRecordingGateway openAi,
            EquipmentIdentifierMatchingService identifierMatching) {
        this.mapper = mapper; this.properties = properties; this.openAi = openAi;
        this.identifierMatching = identifierMatching;
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
        mapper.listIncompleteFiles().forEach(file -> workers.execute(() -> transcribe(file.fileKey())));
    }

    @Override @Transactional
    public RecordingViews.Batch create(List<MultipartFile> uploads, String language) {
        if (uploads == null || uploads.isEmpty()) throw bad("请至少选择一个录音文件。");
        if (uploads.size() > properties.recording().maxFilesPerBatch()) throw bad("单批录音文件数量超过限制。");
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
                        upload.getContentType() == null ? "application/octet-stream" : upload.getContentType(), upload.getSize(), sha, "UPLOADED");
                fileKeys.add(fileKey);
            }
            mapper.updateBatchStatus(batch.id(), "TRANSCRIBING", null);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "录音保存失败。", e); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                fileKeys.forEach(fileKey -> workers.execute(() -> transcribe(fileKey)));
            }
        });
        return getBatch(batchKey);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("录音文件不能为空。");
        if (file.getSize() > properties.recording().maxFileSizeBytes()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "录音文件超过大小限制。");
        if (!EXTENSIONS.contains(extension(file.getOriginalFilename()))) throw bad("不支持的录音格式: " + safeName(file.getOriginalFilename()));
    }

    private void transcribe(String fileKey) {
        RecordingRows.File file = mapper.findFile(fileKey);
        if (file == null) return;
        mapper.updateFileStatus(file.id(), "TRANSCRIBING", null, null);
        try {
            RecordingRows.Batch batch = batchById(file.batchId());
            List<OpenAiRecordingGateway.Transcript> result = openAi.transcribe(resolveContent(file), file.originalName(), file.contentType(), batch.languageCode());
            mapper.deleteSegments(file.id());
            int i=0;
            for (var s : result) mapper.insertSegment(key("rs"), file.id(), i++, s.speaker(), s.startMs(), s.endMs(), s.text(), s.providerId());
            mapper.updateFileStatus(file.id(), "COMPLETED", null, null);
            inferRoles(file.id(), batch.languageCode());
        } catch (Exception e) {
            mapper.updateFileStatus(file.id(), "FAILED", openAi.enabled() ? "TRANSCRIPTION_FAILED" : "PROVIDER_NOT_CONFIGURED", concise(e));
        }
        refreshBatch(file.batchId());
    }

    private void inferRoles(long fileId, String language) {
        List<RecordingRows.Segment> segments = mapper.listSegments(fileId);
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
        if (files.stream().anyMatch(f -> "TRANSCRIBING".equals(f.status()) || "UPLOADED".equals(f.status()))) return;
        long successes = files.stream().filter(f -> "COMPLETED".equals(f.status())).count();
        if (successes == 0) { mapper.updateBatchStatus(batchId, "FAILED", "全部文件转写失败。"); return; }
        mapper.updateBatchStatus(batchId, successes == files.size() ? "TRANSCRIBED" : "PARTIAL_SUCCESS", null);
        if (batch.extractionRevision() == 0) extract(batch.batchKey());
    }

    private synchronized void extract(String batchKey) {
        RecordingRows.Batch batch = requiredBatch(batchKey);
        List<RecordingRows.Segment> segments = mapper.listBatchSegments(batch.id());
        if (segments.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可用于提取的转写原文。");
        mapper.updateBatchStatus(batch.id(), "EXTRACTING", null);
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
    @Override public RecordingViews.Batch retryTranscription(String fileId) {
        RecordingRows.File file=requiredFile(fileId); if (!"FAILED".equals(file.status())) throw conflict("只有失败文件可以重试。");
        mapper.updateFileStatus(file.id(), "UPLOADED", null, null);
        mapper.updateBatchStatus(file.batchId(), "TRANSCRIBING", null);
        workers.execute(() -> transcribe(fileId)); return getBatch(batchById(file.batchId()).batchKey());
    }
    @Override public RecordingViews.Batch retryExtraction(String batchId) {
        RecordingRows.Batch batch = requiredBatch(batchId);
        mapper.updateBatchStatus(batch.id(), "EXTRACTING", null);
        workers.execute(() -> extract(batchId));
        return getBatch(batchId);
    }
    @Override public RecordingViews.Batch setSpeakerRole(String fileId,String speakerLabel,String roleCode) {
        if (!ROLES.contains(roleCode)) throw bad("无效的说话人角色。");
        RecordingRows.File file=requiredFile(fileId);
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
    @Override @Transactional public RecordingViews.Application createApplication(String batchId) {
        RecordingRows.Batch batch=requiredBatch(batchId); if(!"READY".equals(batch.status())||mapper.countPendingCorrections(batch.id(),batch.extractionRevision())>0) throw conflict("仍有设备标识建议待确认。");
        String text=compose(mapper.listIssues(batch.id(),batch.extractionRevision()),batch.languageCode());
        if(text.isBlank()) throw bad("没有可应用的提取内容。"); if(text.length()>4000) throw bad("整理后的问题描述超过 4000 个字符。");
        String key=key("ra"); mapper.insertApplication(key,batch.id(),batch.extractionRevision(),text); return application(mapper.findApplication(key));
    }
    @Override public RecordingViews.Application getApplication(String id){return application(requiredApplication(id));}
    @Override public RecordingViews.Application consumeApplication(String id){var a=requiredApplication(id); if(a.consumedAt()==null&&mapper.consumeApplication(a.id())==0) throw conflict("该录音应用已被消费。"); return application(requiredApplication(id));}
    @Override public void attachUnderstanding(String id,String understandingId){mapper.attachUnderstanding(requiredApplication(id).id(),understandingId);}
    @Override public void attachDiagnosis(String id,String diagnosisId){mapper.attachDiagnosis(requiredApplication(id).id(),diagnosisId);}
    @Override public RecordingRows.File getFile(String id){return requiredFile(id);}
    @Override public Path resolveContent(RecordingRows.File file){Path p=safePath(file.storageKey()); if(!Files.isRegularFile(p)) throw notFound("录音文件不存在。"); return p;}
    @Override @Transactional public void deleteFile(String id){var f=requiredFile(id); if(mapper.countApplications(f.batchId())>0)throw conflict("录音已生成诊断应用，不能删除。"); try{Files.deleteIfExists(resolveContent(f));}catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"录音删除失败。",e);} mapper.deleteFile(f.id());}

    private RecordingViews.Batch view(RecordingRows.Batch batch) {
        List<RecordingRows.File> files=mapper.listFiles(batch.id());
        List<RecordingRows.Issue> issues=batch.extractionRevision()>0?mapper.listIssues(batch.id(),batch.extractionRevision()):List.of();
        Map<Long,RecordingRows.Correction> corrections=new HashMap<>();
        if(batch.extractionRevision()>0) mapper.listCorrections(batch.id(),batch.extractionRevision()).forEach(c->corrections.put(c.issueId(),c));
        Map<String,List<RecordingViews.Evidence>> evidence=new HashMap<>();
        if(batch.extractionRevision()>0) for(var e:mapper.listEvidence(batch.id(),batch.extractionRevision())) evidence.computeIfAbsent(e.issueKey(),k->new ArrayList<>()).add(new RecordingViews.Evidence(e.fileKey(),e.originalName(),e.segmentKey(),e.speakerLabel(),e.speakerRoleCode(),e.startMs(),e.endMs(),e.originalText()));
        return new RecordingViews.Batch(batch.batchKey(),batch.languageCode(),batch.status(),batch.extractionRevision(),batch.extractionError(),
            files.stream().map(f->new RecordingViews.File(f.fileKey(),f.originalName(),f.contentType(),f.sizeBytes(),f.status(),f.errorCode(),f.errorDetail(),mapper.listSegments(f.id()).stream().map(s->new RecordingViews.Segment(s.segmentKey(),s.sequenceNo(),s.speakerLabel(),s.speakerRoleCode(),s.speakerRoleConfidence(),s.speakerRoleSource(),s.startMs(),s.endMs(),s.originalText())).toList())).toList(),
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
    private String compose(List<RecordingRows.Issue> issues,String language){Map<String,List<String>> grouped=new LinkedHashMap<>();TYPE_ORDER.forEach(t->grouped.put(t,new ArrayList<>()));issues.stream().filter(i->!i.deleted()).sorted(Comparator.comparingInt(RecordingRows.Issue::displayOrder)).forEach(i->{String type="OTHER".equals(i.issueType())?"RECENT_CHANGES":i.issueType();String content=trimTerminalPunctuation(i.content());List<String> values=grouped.get(type);if(values!=null&&!content.isBlank()&&!values.contains(content))values.add(content);});Map<String,String> zh=Map.of("MODEL","设备型号","SYMPTOM","主要症状","ERROR_CODE","错误码","OPERATING_STATUS","当前运行状态","OCCURRENCE","发生时间 / 频率","MEASUREMENT","现场测量值","ENVIRONMENT","安装环境","RECENT_CHANGES","近期变化","PHOTO_EVIDENCE","现场照片");Map<String,String> ja=Map.of("MODEL","機器型式","SYMPTOM","主な症状","ERROR_CODE","エラーコード","OPERATING_STATUS","現在の運転状態","OCCURRENCE","発生時期・頻度","MEASUREMENT","現場測定値","ENVIRONMENT","設置環境","RECENT_CHANGES","最近の変更","PHOTO_EVIDENCE","現場写真");Map<String,String> labels="ja-JP".equals(language)?ja:zh;String result=grouped.entrySet().stream().filter(e->!e.getValue().isEmpty()).map(e->labels.get(e.getKey())+"："+String.join("、",e.getValue())).collect(java.util.stream.Collectors.joining("。"));return result.isBlank()?"":result+"。";}
    private String trimTerminalPunctuation(String value){return value==null?"":value.strip().replaceFirst("[。．.!！?？]+$","");}
    private RecordingRows.Batch requiredBatch(String key){var b=mapper.findBatch(key);if(b==null)throw notFound("录音批次不存在。");return b;}
    private RecordingRows.Batch batchById(long id){var b=mapper.findBatchById(id);if(b==null)throw notFound("录音批次不存在。");return b;}
    private RecordingRows.File requiredFile(String key){var f=mapper.findFile(key);if(f==null)throw notFound("录音文件不存在。");return f;}
    private RecordingRows.Issue requiredIssue(RecordingRows.Batch b,String key){var i=mapper.findIssue(b.id(),key);if(i==null)throw notFound("提取项不存在。");return i;}
    private RecordingRows.Application requiredApplication(String key){var a=mapper.findApplication(key);if(a==null)throw notFound("录音应用不存在。");return a;}
    private RecordingViews.Application application(RecordingRows.Application a){return new RecordingViews.Application(a.applicationKey(),a.composedText(),a.status(),a.problemUnderstandingKey(),a.diagnosisSessionKey(),a.consumedAt()!=null);}
    private Path safePath(String storageKey){Path path=root.resolve(storageKey).normalize();if(!path.startsWith(root))throw bad("非法存储路径。");return path;}
    private String extension(String name){String value=name==null?"":name;int dot=value.lastIndexOf('.');return dot<0?"":value.substring(dot+1).toLowerCase(Locale.ROOT);}
    private String safeName(String name){String value=name==null?"recording":""+name;value=value.replace('\\','_').replace('/','_').replaceAll("[\\p{Cntrl}]","_");return value.length()>512?value.substring(value.length()-512):value;}
    private String sha256(Path path)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}return java.util.HexFormat.of().formatHex(digest.digest());}
    private String key(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    private String concise(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(900,m.length()));}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,m);} private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);} private ResponseStatusException notFound(String m){return new ResponseStatusException(HttpStatus.NOT_FOUND,m);}
}
