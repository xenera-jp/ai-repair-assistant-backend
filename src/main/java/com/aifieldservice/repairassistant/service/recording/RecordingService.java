package com.aifieldservice.repairassistant.service.recording;

import java.nio.file.Path;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;

public interface RecordingService {
    record CorrectionDecision(String issueId, int version, String decision, String value) {}
    RecordingViews.Batch create(List<MultipartFile> files, String language);
    RecordingViews.Batch getBatch(String batchId);
    RecordingViews.Batch retryTranscription(String fileId);
    RecordingViews.Batch retryExtraction(String batchId);
    RecordingViews.Batch setSpeakerRole(String fileId, String speakerLabel, String roleCode);
    RecordingViews.Batch createIssue(String batchId, String type, String content);
    RecordingViews.Batch updateIssue(String batchId, String issueId, String content, int version);
    RecordingViews.Batch setIssueDeleted(String batchId, String issueId, boolean deleted);
    RecordingViews.Batch confirmCorrections(String batchId, List<CorrectionDecision> decisions);
    RecordingViews.Application createApplication(String batchId);
    RecordingViews.Application getApplication(String applicationId);
    RecordingViews.Application consumeApplication(String applicationId);
    void attachUnderstanding(String applicationId, String understandingId);
    void attachDiagnosis(String applicationId, String diagnosisId);
    RecordingRows.File getFile(String fileId);
    Path resolveContent(RecordingRows.File file);
    void deleteFile(String fileId);
}
