-- Lightweight state stores for the first complete interactive V1.
-- They reference the immutable diagnosis snapshots produced by V5.

CREATE TABLE onsite_session_state_v1 (
    session_key CHAR(36) NOT NULL,
    parent_session_key CHAR(36) NOT NULL,
    current_round TINYINT UNSIGNED NOT NULL DEFAULT 1,
    max_rounds TINYINT UNSIGNED NOT NULL DEFAULT 3,
    answered_signals_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_key),
    KEY idx_onsite_state_parent (parent_session_key),
    CONSTRAINT fk_onsite_state_session
        FOREIGN KEY (session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key) ON DELETE CASCADE,
    CONSTRAINT fk_onsite_state_parent
        FOREIGN KEY (parent_session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key) ON DELETE CASCADE,
    CONSTRAINT chk_onsite_state_round
        CHECK (
            current_round BETWEEN 1 AND 3 AND
            max_rounds BETWEEN 1 AND 3 AND
            current_round <= max_rounds
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE saved_diagnosis_report_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_key CHAR(36) NOT NULL,
    session_key CHAR(36) NOT NULL,
    report_name VARCHAR(255) NOT NULL,
    note TEXT NULL,
    stage VARCHAR(24) NOT NULL,
    diagnosis_status VARCHAR(32) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_saved_report_key (report_key),
    UNIQUE KEY uk_saved_report_session (session_key),
    KEY idx_saved_report_created (created_at),
    CONSTRAINT fk_saved_report_session
        FOREIGN KEY (session_key)
        REFERENCES diagnosis_snapshot_v1 (session_key),
    CONSTRAINT chk_saved_report_stage
        CHECK (stage IN ('PRE_DEPARTURE', 'ONSITE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
