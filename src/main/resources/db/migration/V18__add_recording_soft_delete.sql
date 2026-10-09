ALTER TABLE recording_batch
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL;

ALTER TABLE recording_file
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL,
    ADD COLUMN content_purged_at DATETIME(6) NULL;

ALTER TABLE recording_transcript_segment
    DROP INDEX uk_transcript_segment_order,
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL,
    ADD KEY idx_transcript_segment_file_order (recording_file_id, deleted, sequence_no);

ALTER TABLE recording_issue_evidence
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL;

ALTER TABLE recording_extracted_issue
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL;

ALTER TABLE recording_identifier_correction
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL;

ALTER TABLE recording_application
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by VARCHAR(64) NULL,
    ADD COLUMN delete_reason VARCHAR(64) NULL;

