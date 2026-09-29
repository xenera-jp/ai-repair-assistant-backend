package com.aifieldservice.repairassistant.domain.recording.model;

import java.time.LocalDateTime;

/** MyBatis rows used by the recording application service. */
public final class RecordingRows {
    private RecordingRows() {}

    public record Batch(long id, String batchKey, String languageCode, String status,
            int extractionRevision, String extractionError, LocalDateTime createdAt) {}

    public record File(long id, String fileKey, long batchId, int displayOrder,
            String originalName, String storageKey, String contentType, long sizeBytes,
            String sha256, String status, String errorCode, String errorDetail) {}

    public record Segment(long id, String segmentKey, long recordingFileId, int sequenceNo,
            String speakerLabel, String speakerRoleCode, Double speakerRoleConfidence,
            String speakerRoleSource, long startMs, long endMs, String originalText,
            String providerSegmentId) {}

    public record Issue(long id, String issueKey, long batchId, int revision, String issueType,
            String content, String originalContent, boolean editedByUser, boolean deleted,
            int displayOrder, int versionNo, LocalDateTime updatedAt) {}

    public record Evidence(String issueKey, String fileKey, String originalName,
            String segmentKey, String speakerLabel, String speakerRoleCode,
            long startMs, long endMs, String originalText, int evidenceOrder) {}

    public record Correction(long id, String correctionKey, long batchId, int revision, long issueId,
            String fieldType, String originalValue, String suggestedValue, String modelValue,
            String sourceText, String evidenceSegmentIdsJson, String candidatesJson,
            Double ruleScore, Double scoreMargin, String reason, String status,
            LocalDateTime confirmedAt) {}

    public record Application(long id, String applicationKey, long batchId,
            int extractionRevision, String composedText, String status,
            String problemUnderstandingKey, String diagnosisSessionKey, LocalDateTime consumedAt) {}
}
