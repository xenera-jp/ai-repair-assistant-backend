package com.aifieldservice.repairassistant.service.recording;

import java.nio.file.Path;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;

public interface RecordingService {
    record CorrectionDecision(String issueId, int version, String decision, String value) {}
    RecordingViews.Batch create(List<MultipartFile> files, String language);
    RecordingViews.Batch createRealtime(List<MultipartFile> files, String language);
    RecordingViews.Batch beginRealtime(String fileId);
    void saveRealtimeSegment(String fileId, String itemId, int sequence, long startMs, long endMs, String speaker, String text);
    void finishRealtime(String fileId);
    void replaceConfirmed(String fileId, java.util.List<com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript> segments);
    void diarizationFailed(String fileId, String detail);
    void resumeDiarization(String fileId);
    void failRealtime(String fileId, String detail);
    RecordingViews.Batch getBatch(String batchId);
    RecordingViews.Batch retryTranscription(String fileId);
    RecordingViews.Batch retryExtraction(String batchId);
    RecordingViews.Batch retryExtraction(String batchId, String conversationVersion);
    RecordingViews.Batch setSpeakerRole(String fileId, String speakerLabel, String roleCode);
    RecordingViews.Batch setSegmentSpeaker(String fileId, String segmentId, String speakerLabel);
    RecordingViews.Batch setSegmentSpeakers(String fileId, List<String> segmentIds, String speakerLabel);
    RecordingViews.Batch createIssue(String batchId, String type, String content);
    RecordingViews.Batch updateIssue(String batchId, String issueId, String content, int version);
    RecordingViews.Batch setIssueDeleted(String batchId, String issueId, boolean deleted);
    RecordingViews.Batch confirmCorrections(String batchId, List<CorrectionDecision> decisions);
    RecordingRows.File getFile(String fileId);
    Path resolveContent(RecordingRows.File file);
    void deleteFile(String fileId);
}
