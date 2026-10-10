CREATE TABLE recording_diarization_window (
    window_key VARCHAR(64) PRIMARY KEY,
    session_key VARCHAR(64) NOT NULL,
    file_key VARCHAR(64) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    overlap_ms BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    result_json LONGTEXT NULL,
    error_detail VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_diarization_file (file_key, start_ms)
);
