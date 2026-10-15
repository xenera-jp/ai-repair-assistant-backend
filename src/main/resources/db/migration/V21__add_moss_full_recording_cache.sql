CREATE TABLE recording_moss_transcription (
    job_key CHAR(64) PRIMARY KEY,
    original_name VARCHAR(512) NOT NULL,
    source_file_key VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json LONGTEXT NOT NULL,
    error_detail VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE recording_moss_playback (
    file_key VARCHAR(64) PRIMARY KEY,
    job_key CHAR(64) NOT NULL,
    position_ms BIGINT NOT NULL DEFAULT 0,
    ended BOOLEAN NOT NULL DEFAULT FALSE,
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_moss_playback_job (job_key)
);
