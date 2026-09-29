CREATE TABLE recording_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_key VARCHAR(64) NOT NULL,
    language_code VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    extraction_revision INT NOT NULL DEFAULT 0,
    extraction_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recording_batch_key (batch_key)
);

CREATE TABLE recording_file (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_key VARCHAR(64) NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    display_order INT NOT NULL,
    original_name VARCHAR(512) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_detail VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recording_file_key (file_key),
    UNIQUE KEY uk_recording_file_order (batch_id, display_order),
    CONSTRAINT fk_recording_file_batch FOREIGN KEY (batch_id) REFERENCES recording_batch(id) ON DELETE CASCADE
);

CREATE TABLE recording_transcript_segment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    segment_key VARCHAR(64) NOT NULL,
    recording_file_id BIGINT UNSIGNED NOT NULL,
    sequence_no INT NOT NULL,
    speaker_label VARCHAR(64) NOT NULL,
    speaker_role_code VARCHAR(32) NULL,
    speaker_role_confidence DECIMAL(5,4) NULL,
    speaker_role_source VARCHAR(16) NOT NULL DEFAULT 'NONE',
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    original_text TEXT NOT NULL,
    provider_segment_id VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transcript_segment_key (segment_key),
    UNIQUE KEY uk_transcript_segment_order (recording_file_id, sequence_no),
    CONSTRAINT fk_transcript_segment_file FOREIGN KEY (recording_file_id) REFERENCES recording_file(id) ON DELETE CASCADE
);

CREATE TABLE recording_extracted_issue (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    issue_key VARCHAR(64) NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    revision INT NOT NULL,
    issue_type VARCHAR(32) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    original_content VARCHAR(1000) NOT NULL,
    edited_by_user BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recording_issue_key (issue_key),
    KEY idx_recording_issue_batch_revision (batch_id, revision, display_order),
    CONSTRAINT fk_recording_issue_batch FOREIGN KEY (batch_id) REFERENCES recording_batch(id) ON DELETE CASCADE
);

CREATE TABLE recording_issue_evidence (
    issue_id BIGINT UNSIGNED NOT NULL,
    segment_id BIGINT UNSIGNED NOT NULL,
    evidence_order INT NOT NULL,
    PRIMARY KEY (issue_id, segment_id),
    CONSTRAINT fk_issue_evidence_issue FOREIGN KEY (issue_id) REFERENCES recording_extracted_issue(id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_evidence_segment FOREIGN KEY (segment_id) REFERENCES recording_transcript_segment(id) ON DELETE CASCADE
);

CREATE TABLE recording_application (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    application_key VARCHAR(64) NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    extraction_revision INT NOT NULL,
    composed_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    problem_understanding_key VARCHAR(64) NULL,
    diagnosis_session_key VARCHAR(64) NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recording_application_key (application_key),
    KEY idx_recording_application_batch (batch_id),
    CONSTRAINT fk_recording_application_batch FOREIGN KEY (batch_id) REFERENCES recording_batch(id)
);
