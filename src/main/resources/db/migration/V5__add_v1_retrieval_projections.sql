-- Materialized read models for the first end-to-end diagnosis slice.
-- Canonical source rows and knowledge-unit versions remain the source of truth.

CREATE TABLE repair_case_projection_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    reception_id VARCHAR(64) NOT NULL,
    model VARCHAR(96) NOT NULL,
    serial_number VARCHAR(128) NOT NULL,
    customer_site_name VARCHAR(255) NOT NULL,
    received_at DATETIME(3) NULL,
    problem_type_code VARCHAR(80) NOT NULL,
    problem_type_label VARCHAR(160) NOT NULL,
    error_codes_json JSON NOT NULL,
    complaint LONGTEXT NOT NULL,
    onsite_observation LONGTEXT NULL,
    cause_text LONGTEXT NULL,
    action_text LONGTEXT NULL,
    final_resolved BOOLEAN NULL,
    first_fix BOOLEAN NULL,
    visit_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    total_duration_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    parts_json JSON NOT NULL,
    problem_projection LONGTEXT NOT NULL,
    resolution_projection LONGTEXT NOT NULL,
    source_reference VARCHAR(500) NOT NULL,
    qdrant_point_id CHAR(36) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    indexed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_case_reception (reception_id),
    UNIQUE KEY uk_repair_case_knowledge_version (knowledge_unit_version_id),
    UNIQUE KEY uk_repair_case_qdrant_point (qdrant_point_id),
    KEY idx_repair_case_structured (
        model, problem_type_code, final_resolved, first_fix
    ),
    KEY idx_repair_case_received (received_at),
    CONSTRAINT fk_repair_case_knowledge_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT chk_repair_case_trust
        CHECK (trust_level IN ('VERIFIED_CASE', 'OBSERVED_CASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE problem_understanding_snapshot_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    understanding_key CHAR(36) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    language_code VARCHAR(12) NOT NULL,
    original_text LONGTEXT NOT NULL,
    primary_problem_type_code VARCHAR(80) NULL,
    ready_for_analysis BOOLEAN NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_problem_understanding_snapshot_key (understanding_key),
    KEY idx_problem_understanding_created (created_at),
    CONSTRAINT chk_problem_understanding_snapshot_stage
        CHECK (stage IN ('PRE_DEPARTURE', 'ONSITE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_snapshot_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_key CHAR(36) NOT NULL,
    understanding_key CHAR(36) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_snapshot_session (session_key),
    KEY idx_diagnosis_snapshot_understanding (understanding_key),
    KEY idx_diagnosis_snapshot_updated (updated_at),
    CONSTRAINT chk_diagnosis_snapshot_stage
        CHECK (stage IN ('PRE_DEPARTURE', 'ONSITE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
