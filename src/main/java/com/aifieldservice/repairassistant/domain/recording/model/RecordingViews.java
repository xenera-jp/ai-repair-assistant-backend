package com.aifieldservice.repairassistant.domain.recording.model;

import java.time.LocalDateTime;
import java.util.List;

/** API-neutral views assembled by the recording service. */
public final class RecordingViews {
    private RecordingViews() {}
    public record Batch(String id, String language, String status, int extractionRevision,
            String extractionError, List<File> files, List<Issue> issues, LocalDateTime createdAt) {}
    public record File(String id, String name, String contentType, long sizeBytes, String status,
            String errorCode, String errorMessage, List<Segment> segments) {}
    public record Segment(String id, int sequenceNo, String speakerLabel, String roleCode,
            Double roleConfidence, String roleSource, long startMs, long endMs, String text) {}
    public record Issue(String id, String type, String content, String originalContent,
            boolean editedByUser, boolean deleted, int version, List<Evidence> evidence,
            Correction correction) {}
    public record Correction(String status, String originalValue, String suggestedValue,
            String model, String sourceText, Double ruleScore, String reason,
            List<String> evidenceSegmentIds) {}
    public record Evidence(String fileId, String fileName, String segmentId, String speakerLabel,
            String roleCode, long startMs, long endMs, String text) {}
    public record Application(String id, String composedText, String status,
            String problemUnderstandingId, String diagnosisSessionId, boolean consumed) {}
}
