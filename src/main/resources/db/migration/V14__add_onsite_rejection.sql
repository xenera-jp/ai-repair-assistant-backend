CREATE TABLE onsite_rejection_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    onsite_session_key CHAR(36) NOT NULL,
    rejected_session_key CHAR(36) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    rejected_candidate_codes JSON NOT NULL,
    reason_code VARCHAR(64) NULL,
    reason_text TEXT NULL,
    onsite_observation TEXT NOT NULL,
    rediagnosed_session_key CHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_onsite_rejection_session (onsite_session_key),
    KEY idx_onsite_rejection_rejected (rejected_session_key),
    KEY idx_onsite_rejection_rediagnosed (rediagnosed_session_key),
    CONSTRAINT fk_onsite_rejection_onsite FOREIGN KEY (onsite_session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key),
    CONSTRAINT fk_onsite_rejection_rejected FOREIGN KEY (rejected_session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key),
    CONSTRAINT fk_onsite_rejection_rediagnosed FOREIGN KEY (rediagnosed_session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key),
    CONSTRAINT chk_onsite_rejection_scope CHECK (scope IN ('WHOLE', 'CANDIDATES'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
