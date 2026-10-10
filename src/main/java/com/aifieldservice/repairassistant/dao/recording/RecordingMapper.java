package com.aifieldservice.repairassistant.dao.recording;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;

/** MyBatis access point for recording batches, transcripts and extracted issues. */
public interface RecordingMapper {
    @org.apache.ibatis.annotations.Update("UPDATE recording_transcript_segment SET deleted=TRUE,deleted_at=CURRENT_TIMESTAMP(6),delete_reason='DIARIZATION_REVISION' WHERE id=#{id} AND deleted=FALSE")
    int retireSegment(@Param("id") long id);
    @org.apache.ibatis.annotations.Update("UPDATE recording_transcript_segment SET sequence_no=#{sequence} WHERE id=#{id} AND deleted=FALSE")
    int orderSegment(@Param("id") long id,@Param("sequence") int sequence);
    int insertBatch(@Param("batchKey") String batchKey, @Param("languageCode") String languageCode,
            @Param("status") String status);
    RecordingRows.Batch findBatch(@Param("batchKey") String batchKey);
    RecordingRows.Batch findBatchIncludingDeleted(@Param("batchKey") String batchKey);
    RecordingRows.Batch findBatchById(@Param("batchId") long batchId);
    RecordingRows.Batch findBatchByIdForUpdate(@Param("batchId") long batchId);
    int updateBatchStatus(@Param("batchId") long batchId, @Param("status") String status,
            @Param("error") String error);
    int incrementExtractionRevision(@Param("batchId") long batchId);
    int claimExtraction(@Param("batchId") long batchId);
    int markRealtime(@Param("fileId") long fileId);
    boolean isRealtime(@Param("fileId") long fileId);

    int insertFile(@Param("fileKey") String fileKey, @Param("batchId") long batchId,
            @Param("displayOrder") int displayOrder, @Param("originalName") String originalName,
            @Param("storageKey") String storageKey, @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
            @Param("status") String status);
    RecordingRows.File findFile(@Param("fileKey") String fileKey);
    RecordingRows.File findFileIncludingDeleted(@Param("fileKey") String fileKey);
    RecordingRows.File findFileIncludingDeletedForUpdate(@Param("fileKey") String fileKey);
    List<RecordingRows.File> listFiles(@Param("batchId") long batchId);
    List<RecordingRows.File> listIncompleteFiles();
    int updateFileStatus(@Param("fileId") long fileId, @Param("status") String status,
            @Param("errorCode") String errorCode, @Param("errorDetail") String errorDetail);
    int softDeleteBatch(@Param("batchId") long batchId, @Param("reason") String reason);
    int softDeleteFile(@Param("fileId") long fileId, @Param("reason") String reason);
    int softDeleteIssues(@Param("batchId") long batchId, @Param("reason") String reason);
    int softDeleteEvidence(@Param("batchId") long batchId, @Param("reason") String reason);
    int softDeleteCorrections(@Param("batchId") long batchId, @Param("reason") String reason);

    int softDeleteSegments(@Param("fileId") long fileId, @Param("reason") String reason);
    int insertSegment(@Param("segmentKey") String segmentKey, @Param("fileId") long fileId,
            @Param("sequenceNo") int sequenceNo, @Param("speakerLabel") String speakerLabel,
            @Param("startMs") long startMs, @Param("endMs") long endMs,
            @Param("originalText") String originalText, @Param("providerSegmentId") String providerSegmentId,
            @Param("deleted") boolean deleted);
    List<RecordingRows.Segment> listSegments(@Param("fileId") long fileId);
    List<RecordingRows.Segment> listBatchSegments(@Param("batchId") long batchId);
    int updateSpeakerRole(@Param("fileId") long fileId, @Param("speakerLabel") String speakerLabel,
            @Param("roleCode") String roleCode, @Param("confidence") Double confidence,
            @Param("source") String source);
    int updateSegmentSpeaker(@Param("fileId") long fileId, @Param("segmentKey") String segmentKey,
            @Param("speakerLabel") String speakerLabel, @Param("roleCode") String roleCode);

    int deleteCurrentIssues(@Param("batchId") long batchId, @Param("revision") int revision);
    int insertIssue(@Param("issueKey") String issueKey, @Param("batchId") long batchId,
            @Param("revision") int revision, @Param("issueType") String issueType,
            @Param("content") String content, @Param("originalContent") String originalContent,
            @Param("displayOrder") int displayOrder);
    RecordingRows.Issue findIssue(@Param("batchId") long batchId, @Param("issueKey") String issueKey);
    List<RecordingRows.Issue> listIssues(@Param("batchId") long batchId, @Param("revision") int revision);
    int insertEvidence(@Param("issueId") long issueId, @Param("segmentId") long segmentId,
            @Param("evidenceOrder") int evidenceOrder);
    List<RecordingRows.Evidence> listEvidence(@Param("batchId") long batchId, @Param("revision") int revision);
    int updateIssueContent(@Param("issueId") long issueId, @Param("content") String content,
            @Param("versionNo") int versionNo);
    int updateIssueDeleted(@Param("issueId") long issueId, @Param("deleted") boolean deleted);
    int insertCorrection(@Param("correctionKey") String correctionKey, @Param("batchId") long batchId,
            @Param("revision") int revision, @Param("issueId") long issueId,
            @Param("fieldType") String fieldType, @Param("originalValue") String originalValue,
            @Param("suggestedValue") String suggestedValue, @Param("modelValue") String modelValue,
            @Param("sourceText") String sourceText, @Param("evidenceJson") String evidenceJson,
            @Param("candidatesJson") String candidatesJson, @Param("ruleScore") Double ruleScore,
            @Param("scoreMargin") Double scoreMargin, @Param("reason") String reason,
            @Param("status") String status);
    List<RecordingRows.Correction> listCorrections(@Param("batchId") long batchId, @Param("revision") int revision);
    RecordingRows.Correction findCorrectionForIssue(@Param("issueId") long issueId);
    int resolveCorrection(@Param("correctionId") long correctionId, @Param("status") String status);
    int invalidatePendingCorrection(@Param("issueId") long issueId);
    int countPendingCorrections(@Param("batchId") long batchId, @Param("revision") int revision);
    int applyCorrectionContent(@Param("issueId") long issueId, @Param("content") String content,
            @Param("versionNo") int versionNo);

}
