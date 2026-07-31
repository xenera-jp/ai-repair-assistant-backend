-- AI Repair Assistant - MySQL 8.x schema V1
-- Flyway migration: V1__init_ai_repair_assistant.sql

SET NAMES utf8mb4;

CREATE TABLE knowledge_base (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_code (code),
    CONSTRAINT chk_knowledge_base_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ingestion_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    batch_key CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    total_files INT UNSIGNED NOT NULL DEFAULT 0,
    total_records INT UNSIGNED NOT NULL DEFAULT 0,
    error_count INT UNSIGNED NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ingestion_batch_key (batch_key),
    KEY idx_ingestion_batch_status (knowledge_base_id, status, created_at),
    CONSTRAINT fk_ingestion_batch_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT chk_ingestion_batch_status
        CHECK (status IN (
            'PENDING', 'PROCESSING', 'COMPLETED',
            'COMPLETED_WITH_ERRORS', 'FAILED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_file (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    ingestion_batch_id BIGINT UNSIGNED NOT NULL,
    supersedes_file_id BIGINT UNSIGNED NULL,
    logical_document_key VARCHAR(191) NULL,
    original_file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(24) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    language_code VARCHAR(12) NULL,
    sha256 CHAR(64) NOT NULL,
    file_size_bytes BIGINT UNSIGNED NOT NULL,
    parser_version VARCHAR(40) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_file_hash (knowledge_base_id, sha256),
    KEY idx_source_file_kind (
        knowledge_base_id, source_kind, status, created_at
    ),
    KEY idx_source_file_logical_key (
        knowledge_base_id, logical_document_key
    ),
    CONSTRAINT fk_source_file_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_source_file_batch
        FOREIGN KEY (ingestion_batch_id) REFERENCES ingestion_batch (id),
    CONSTRAINT fk_source_file_supersedes
        FOREIGN KEY (supersedes_file_id) REFERENCES source_file (id),
    CONSTRAINT chk_source_file_type
        CHECK (file_type IN ('XLSX', 'XLS', 'CSV', 'PDF', 'DOCX', 'TXT', 'MD')),
    CONSTRAINT chk_source_file_kind
        CHECK (source_kind IN (
            'CALL_HISTORY', 'REPAIR_HISTORY', 'PART_USAGE_HISTORY',
            'SERVICE_MANUAL', 'PARTS_MANUAL', 'CUSTOMER_COMMUNICATION',
            'OTHER'
        )),
    CONSTRAINT chk_source_file_status
        CHECK (status IN (
            'UPLOADED', 'PARSING', 'PARSED', 'VALIDATED',
            'REVIEW_REQUIRED', 'FAILED', 'SUPERSEDED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_file_id BIGINT UNSIGNED NOT NULL,
    record_type VARCHAR(40) NOT NULL,
    business_key VARCHAR(191) NOT NULL,
    sheet_name VARCHAR(128) NULL,
    source_row_no INT UNSIGNED NULL,
    source_cell_range VARCHAR(64) NULL,
    raw_payload JSON NOT NULL,
    record_fingerprint CHAR(64) NOT NULL,
    validation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    validation_messages JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_record_position (
        source_file_id, sheet_name, source_row_no
    ),
    UNIQUE KEY uk_source_record_business_key (
        source_file_id, record_type, business_key
    ),
    KEY idx_source_record_validation (
        source_file_id, validation_status
    ),
    KEY idx_source_record_fingerprint (record_fingerprint),
    CONSTRAINT fk_source_record_file
        FOREIGN KEY (source_file_id) REFERENCES source_file (id),
    CONSTRAINT chk_source_record_validation
        CHECK (validation_status IN (
            'PENDING', 'VALID', 'WARNING', 'INVALID'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE taxonomy_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    version_code VARCHAR(40) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    effective_from DATETIME(3) NULL,
    effective_to DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_taxonomy_version_code (version_code),
    UNIQUE KEY uk_taxonomy_version_no (version_no),
    CONSTRAINT chk_taxonomy_version_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE problem_domain (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    taxonomy_version_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(64) NOT NULL,
    name_zh VARCHAR(160) NOT NULL,
    name_ja VARCHAR(160) NULL,
    name_en VARCHAR(160) NULL,
    description TEXT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_problem_domain_code (taxonomy_version_id, code),
    CONSTRAINT fk_problem_domain_taxonomy
        FOREIGN KEY (taxonomy_version_id) REFERENCES taxonomy_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retrieval_strategy (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    taxonomy_version_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    config_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_retrieval_strategy_code (taxonomy_version_id, code),
    CONSTRAINT fk_retrieval_strategy_taxonomy
        FOREIGN KEY (taxonomy_version_id) REFERENCES taxonomy_version (id),
    CONSTRAINT chk_retrieval_strategy_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE problem_type (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    taxonomy_version_id BIGINT UNSIGNED NOT NULL,
    problem_domain_id BIGINT UNSIGNED NOT NULL,
    retrieval_strategy_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(80) NOT NULL,
    name_zh VARCHAR(160) NOT NULL,
    name_ja VARCHAR(160) NULL,
    name_en VARCHAR(160) NULL,
    description TEXT NULL,
    source_labels_json JSON NOT NULL,
    aliases_json JSON NULL,
    model_scopes_json JSON NOT NULL,
    error_codes_json JSON NULL,
    clarification_schema_json JSON NULL,
    implementation_priority VARCHAR(8) NOT NULL DEFAULT 'P1',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_problem_type_code (taxonomy_version_id, code),
    KEY idx_problem_type_domain (problem_domain_id, active),
    KEY idx_problem_type_strategy (retrieval_strategy_id),
    CONSTRAINT fk_problem_type_taxonomy
        FOREIGN KEY (taxonomy_version_id) REFERENCES taxonomy_version (id),
    CONSTRAINT fk_problem_type_domain
        FOREIGN KEY (problem_domain_id) REFERENCES problem_domain (id),
    CONSTRAINT fk_problem_type_strategy
        FOREIGN KEY (retrieval_strategy_id) REFERENCES retrieval_strategy (id),
    CONSTRAINT chk_problem_type_priority
        CHECK (implementation_priority IN ('P0', 'P1', 'P2'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cause_hypothesis (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    problem_type_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(96) NOT NULL,
    name_zh VARCHAR(160) NOT NULL,
    name_ja VARCHAR(160) NULL,
    name_en VARCHAR(160) NULL,
    description TEXT NULL,
    default_rank SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    supporting_signals_json JSON NOT NULL,
    conflicting_signals_json JSON NULL,
    clarification_questions_json JSON NULL,
    action_boundary_json JSON NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cause_hypothesis_code (problem_type_id, code),
    KEY idx_cause_hypothesis_rank (
        problem_type_id, active, default_rank
    ),
    CONSTRAINT fk_cause_hypothesis_problem
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_cause_hypothesis_rank CHECK (default_rank BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE asset (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    product_family VARCHAR(64) NOT NULL,
    model VARCHAR(96) NOT NULL,
    serial_number VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_model_serial (
        knowledge_base_id, model, serial_number
    ),
    KEY idx_asset_model (knowledge_base_id, model),
    CONSTRAINT fk_asset_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE maintenance_incident (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    asset_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    source_reception_id VARCHAR(64) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    customer_site_name VARCHAR(255) NOT NULL,
    business_type VARCHAR(96) NULL,
    region VARCHAR(64) NULL,
    priority VARCHAR(16) NOT NULL,
    response_type VARCHAR(40) NULL,
    source_status VARCHAR(40) NULL,
    related_work_id_raw VARCHAR(64) NULL,
    data_status VARCHAR(24) NOT NULL DEFAULT 'COMPLETE',
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_incident_reception (
        knowledge_base_id, source_reception_id
    ),
    UNIQUE KEY uk_incident_source_record (source_record_id),
    KEY idx_incident_asset_time (asset_id, received_at),
    KEY idx_incident_priority (
        knowledge_base_id, priority, received_at
    ),
    CONSTRAINT fk_incident_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_incident_asset
        FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_incident_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_incident_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_incident_data_status
        CHECK (data_status IN (
            'COMPLETE', 'INCOMPLETE', 'CONFLICT', 'REVIEW_REQUIRED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE call_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    agent_name VARCHAR(96) NULL,
    complaint_raw LONGTEXT NOT NULL,
    initial_category_raw VARCHAR(255) NULL,
    initial_action_raw TEXT NULL,
    dispatched_ce_id VARCHAR(32) NULL,
    dispatched_ce_name VARCHAR(96) NULL,
    handling_minutes INT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_call_record_incident (incident_id),
    UNIQUE KEY uk_call_record_source (source_record_id),
    CONSTRAINT fk_call_record_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_call_record_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE incident_problem_type (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    problem_type_id BIGINT UNSIGNED NOT NULL,
    rank_no TINYINT UNSIGNED NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    origin VARCHAR(24) NOT NULL,
    confidence DECIMAL(5,4) NULL,
    source_value VARCHAR(255) NULL,
    matched_signals_json JSON NULL,
    conflicting_signals_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_incident_problem_type (
        incident_id, problem_type_id
    ),
    UNIQUE KEY uk_incident_problem_rank (incident_id, rank_no),
    KEY idx_incident_problem_lookup (problem_type_id, is_primary),
    CONSTRAINT fk_incident_problem_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_incident_problem_type
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_incident_problem_rank CHECK (rank_no BETWEEN 1 AND 2),
    CONSTRAINT chk_incident_problem_primary
        CHECK (
            (rank_no = 1 AND is_primary = TRUE) OR
            (rank_no > 1 AND is_primary = FALSE)
        ),
    CONSTRAINT chk_incident_problem_origin
        CHECK (origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'INFERENCE', 'HUMAN_REVIEW'
        )),
    CONSTRAINT chk_incident_problem_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_visit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    incident_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    source_work_id VARCHAR(64) NOT NULL,
    sequence_no SMALLINT UNSIGNED NOT NULL,
    visit_date DATE NOT NULL,
    visit_type VARCHAR(16) NOT NULL,
    technician_source_id VARCHAR(32) NULL,
    technician_name VARCHAR(96) NULL,
    service_region VARCHAR(64) NULL,
    customer_snapshot VARCHAR(255) NULL,
    asset_model_snapshot VARCHAR(96) NOT NULL,
    serial_number_snapshot VARCHAR(128) NOT NULL,
    symptom_mode_raw VARCHAR(255) NULL,
    error_code_raw VARCHAR(128) NULL,
    onsite_phenomenon_raw TEXT NULL,
    cause_raw TEXT NULL,
    action_raw TEXT NULL,
    declared_part_line_count INT UNSIGNED NOT NULL DEFAULT 0,
    used_part_list_raw TEXT NULL,
    part_cost_total DECIMAL(12,2) NOT NULL DEFAULT 0,
    duration_minutes INT UNSIGNED NULL,
    source_resolved BOOLEAN NULL,
    follow_up_note_raw VARCHAR(255) NULL,
    follow_up_reason VARCHAR(40) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_visit_work (
        knowledge_base_id, source_work_id
    ),
    UNIQUE KEY uk_service_visit_source (source_record_id),
    UNIQUE KEY uk_service_visit_sequence (incident_id, sequence_no),
    KEY idx_service_visit_date (incident_id, visit_date),
    CONSTRAINT fk_service_visit_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_service_visit_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_service_visit_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_service_visit_type
        CHECK (visit_type IN ('INITIAL', 'REVISIT')),
    CONSTRAINT chk_service_visit_sequence CHECK (sequence_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE part_usage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    incident_id BIGINT UNSIGNED NOT NULL,
    service_visit_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    source_detail_id VARCHAR(64) NOT NULL,
    asset_model_snapshot VARCHAR(96) NOT NULL,
    visit_date_snapshot DATE NOT NULL,
    part_number VARCHAR(64) NULL,
    part_number_raw VARCHAR(64) NOT NULL,
    part_name_raw VARCHAR(255) NOT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL DEFAULT 0,
    amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_usage_detail (
        knowledge_base_id, source_detail_id
    ),
    UNIQUE KEY uk_part_usage_source (source_record_id),
    KEY idx_part_usage_visit (service_visit_id),
    KEY idx_part_usage_incident_part (incident_id, part_number),
    KEY idx_part_usage_model_part (
        asset_model_snapshot, part_number
    ),
    CONSTRAINT fk_part_usage_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_part_usage_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_part_usage_visit
        FOREIGN KEY (service_visit_id)
        REFERENCES service_visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_part_usage_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_part_usage_quantity CHECK (quantity > 0),
    CONSTRAINT chk_part_usage_prices CHECK (unit_price >= 0 AND amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE symptom_observation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    service_visit_id BIGINT UNSIGNED NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    problem_type_id BIGINT UNSIGNED NULL,
    phase VARCHAR(16) NOT NULL,
    raw_text TEXT NOT NULL,
    normalized_signal_code VARCHAR(96) NULL,
    normalized_value_json JSON NULL,
    origin VARCHAR(24) NOT NULL,
    confidence DECIMAL(5,4) NULL,
    source_field_name VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_symptom_observation_incident (incident_id, phase),
    KEY idx_symptom_observation_problem (problem_type_id),
    CONSTRAINT fk_symptom_observation_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_symptom_observation_visit
        FOREIGN KEY (service_visit_id)
        REFERENCES service_visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_symptom_observation_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT fk_symptom_observation_problem
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_symptom_observation_phase
        CHECK (phase IN ('INTAKE', 'ONSITE', 'FOLLOW_UP')),
    CONSTRAINT chk_symptom_observation_origin
        CHECK (origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'INFERENCE', 'HUMAN_REVIEW'
        )),
    CONSTRAINT chk_symptom_observation_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fault_signal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    service_visit_id BIGINT UNSIGNED NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    signal_type VARCHAR(32) NOT NULL,
    signal_code VARCHAR(96) NULL,
    value_text VARCHAR(255) NULL,
    value_number DECIMAL(18,6) NULL,
    unit VARCHAR(32) NULL,
    origin VARCHAR(24) NOT NULL,
    source_field_name VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_fault_signal_lookup (
        incident_id, signal_type, signal_code
    ),
    KEY idx_fault_signal_code (signal_type, signal_code),
    CONSTRAINT fk_fault_signal_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_fault_signal_visit
        FOREIGN KEY (service_visit_id)
        REFERENCES service_visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_fault_signal_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_fault_signal_type
        CHECK (signal_type IN (
            'ERROR_CODE', 'ALARM', 'MEASUREMENT',
            'OPERATING_STATE', 'VISUAL', 'SOUND'
        )),
    CONSTRAINT chk_fault_signal_origin
        CHECK (origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'INFERENCE', 'HUMAN_REVIEW'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cause_assertion (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    service_visit_id BIGINT UNSIGNED NULL,
    source_record_id BIGINT UNSIGNED NULL,
    problem_type_id BIGINT UNSIGNED NULL,
    cause_code VARCHAR(96) NULL,
    display_name_zh VARCHAR(160) NULL,
    display_name_ja VARCHAR(160) NULL,
    source_text TEXT NULL,
    assertion_level VARCHAR(24) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    confidence DECIMAL(5,4) NULL,
    limitations_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_cause_assertion_incident (
        incident_id, assertion_level
    ),
    KEY idx_cause_assertion_problem (problem_type_id, cause_code),
    CONSTRAINT fk_cause_assertion_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_cause_assertion_visit
        FOREIGN KEY (service_visit_id)
        REFERENCES service_visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_cause_assertion_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT fk_cause_assertion_problem
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_cause_assertion_level
        CHECK (assertion_level IN (
            'SOURCE_CANDIDATE', 'SUPPORTED', 'CONFIRMED', 'REJECTED'
        )),
    CONSTRAINT chk_cause_assertion_origin
        CHECK (origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'DERIVED_FACT',
            'INFERENCE', 'HUMAN_REVIEW'
        )),
    CONSTRAINT chk_cause_assertion_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE repair_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    service_visit_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NULL,
    part_usage_id BIGINT UNSIGNED NULL,
    sequence_no SMALLINT UNSIGNED NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    target_component_code VARCHAR(96) NULL,
    target_component_name VARCHAR(160) NULL,
    source_text TEXT NULL,
    origin VARCHAR(24) NOT NULL,
    outcome_association VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    confidence DECIMAL(5,4) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_action_sequence (
        service_visit_id, sequence_no
    ),
    KEY idx_repair_action_type (action_type, target_component_code),
    CONSTRAINT fk_repair_action_visit
        FOREIGN KEY (service_visit_id)
        REFERENCES service_visit (id) ON DELETE CASCADE,
    CONSTRAINT fk_repair_action_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT fk_repair_action_part
        FOREIGN KEY (part_usage_id) REFERENCES part_usage (id),
    CONSTRAINT chk_repair_action_type
        CHECK (action_type IN (
            'CHECK', 'MEASURE', 'CLEAN', 'ADJUST', 'RESET',
            'REPAIR', 'REPLACE', 'REFILL', 'REWIRE',
            'EXPLAIN', 'OTHER'
        )),
    CONSTRAINT chk_repair_action_origin
        CHECK (origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'INFERENCE', 'HUMAN_REVIEW'
        )),
    CONSTRAINT chk_repair_action_outcome
        CHECK (outcome_association IN (
            'UNKNOWN', 'FAILED_OR_INSUFFICIENT', 'SUCCESS_ASSOCIATED'
        )),
    CONSTRAINT chk_repair_action_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_repair_action_sequence CHECK (sequence_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE incident_outcome (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    final_service_visit_id BIGINT UNSIGNED NULL,
    visit_count SMALLINT UNSIGNED NOT NULL,
    revisit_count SMALLINT UNSIGNED NOT NULL,
    first_fix BOOLEAN NULL,
    final_resolved BOOLEAN NULL,
    total_duration_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    total_part_cost DECIMAL(12,2) NOT NULL DEFAULT 0,
    days_to_resolution INT UNSIGNED NULL,
    origin VARCHAR(24) NOT NULL DEFAULT 'DERIVED_FACT',
    rule_version VARCHAR(40) NOT NULL,
    computed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_incident_outcome_incident (incident_id),
    KEY idx_incident_outcome_result (final_resolved, first_fix),
    CONSTRAINT fk_incident_outcome_incident
        FOREIGN KEY (incident_id)
        REFERENCES maintenance_incident (id) ON DELETE CASCADE,
    CONSTRAINT fk_incident_outcome_final_visit
        FOREIGN KEY (final_service_visit_id) REFERENCES service_visit (id),
    CONSTRAINT chk_incident_outcome_origin
        CHECK (origin = 'DERIVED_FACT'),
    CONSTRAINT chk_incident_outcome_visits
        CHECK (revisit_count <= visit_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_unit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    unit_key VARCHAR(191) NOT NULL,
    unit_type VARCHAR(40) NOT NULL,
    current_version_no INT UNSIGNED NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_unit_key (
        knowledge_base_id, unit_key
    ),
    KEY idx_knowledge_unit_type (
        knowledge_base_id, unit_type
    ),
    CONSTRAINT fk_knowledge_unit_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT chk_knowledge_unit_type
        CHECK (unit_type IN (
            'FAULT_DEFINITION', 'REPAIR_PROCEDURE',
            'PART_REFERENCE', 'REPAIR_CASE',
            'INTAKE_OBSERVATION'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_unit_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_id BIGINT UNSIGNED NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    schema_version VARCHAR(24) NOT NULL,
    title VARCHAR(320) NOT NULL,
    language_code VARCHAR(12) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    content_json JSON NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    qdrant_point_id CHAR(36) NOT NULL,
    published_at DATETIME(3) NULL,
    indexed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_unit_version (
        knowledge_unit_id, version_no
    ),
    UNIQUE KEY uk_knowledge_unit_content (
        knowledge_unit_id, content_fingerprint
    ),
    UNIQUE KEY uk_knowledge_unit_qdrant_point (qdrant_point_id),
    KEY idx_knowledge_version_status (status, trust_level),
    CONSTRAINT fk_knowledge_version_unit
        FOREIGN KEY (knowledge_unit_id)
        REFERENCES knowledge_unit (id) ON DELETE CASCADE,
    CONSTRAINT chk_knowledge_version_trust
        CHECK (trust_level IN (
            'AUTHORITATIVE', 'VERIFIED_CASE',
            'OBSERVED_CASE', 'UNVERIFIED_OBSERVATION'
        )),
    CONSTRAINT chk_knowledge_version_status
        CHECK (status IN (
            'DRAFT', 'NORMALIZED', 'ENRICHED',
            'PUBLISHED', 'REVIEW_REQUIRED',
            'REJECTED', 'SUPERSEDED'
        )),
    CONSTRAINT chk_knowledge_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_unit_problem_type (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    problem_type_id BIGINT UNSIGNED NOT NULL,
    rank_no TINYINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_problem_type (
        knowledge_unit_version_id, problem_type_id
    ),
    UNIQUE KEY uk_knowledge_problem_rank (
        knowledge_unit_version_id, rank_no
    ),
    KEY idx_knowledge_problem_lookup (problem_type_id),
    CONSTRAINT fk_knowledge_problem_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_problem_type
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_knowledge_problem_rank CHECK (rank_no BETWEEN 1 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_unit_source (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    relation_type VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_unit_source (
        knowledge_unit_version_id, source_record_id
    ),
    KEY idx_knowledge_source_record (source_record_id),
    CONSTRAINT fk_knowledge_source_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_record
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_knowledge_source_relation
        CHECK (relation_type IN ('PRIMARY', 'SUPPORTING', 'CONTRADICTING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_claim (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    claim_key VARCHAR(191) NOT NULL,
    claim_origin VARCHAR(24) NOT NULL,
    assertion_level VARCHAR(24) NULL,
    subject_code VARCHAR(160) NULL,
    predicate_code VARCHAR(160) NOT NULL,
    object_json JSON NOT NULL,
    confidence DECIMAL(5,4) NULL,
    limitations_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_claim_key (
        knowledge_unit_version_id, claim_key
    ),
    KEY idx_knowledge_claim_predicate (predicate_code),
    CONSTRAINT fk_knowledge_claim_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT chk_knowledge_claim_origin
        CHECK (claim_origin IN (
            'SOURCE_FACT', 'NORMALIZED_FACT', 'DERIVED_FACT',
            'INFERENCE', 'HUMAN_REVIEW'
        )),
    CONSTRAINT chk_knowledge_claim_assertion
        CHECK (
            assertion_level IS NULL OR
            assertion_level IN (
                'SOURCE_CANDIDATE', 'SUPPORTED', 'CONFIRMED', 'REJECTED'
            )
        ),
    CONSTRAINT chk_knowledge_claim_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_claim_evidence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_claim_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NULL,
    source_field_name VARCHAR(128) NULL,
    derived_rule_code VARCHAR(96) NULL,
    evidence_note TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_claim_evidence_claim (knowledge_claim_id),
    KEY idx_claim_evidence_source (source_record_id),
    CONSTRAINT fk_claim_evidence_claim
        FOREIGN KEY (knowledge_claim_id)
        REFERENCES knowledge_claim (id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_evidence_source
        FOREIGN KEY (source_record_id) REFERENCES source_record (id),
    CONSTRAINT chk_claim_evidence_reference
        CHECK (
            source_record_id IS NOT NULL OR
            derived_rule_code IS NOT NULL OR
            evidence_note IS NOT NULL
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_search_projection (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    projection_type VARCHAR(24) NOT NULL,
    projection_text LONGTEXT NOT NULL,
    projection_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_projection_type (
        knowledge_unit_version_id, projection_type
    ),
    KEY idx_knowledge_projection_hash (projection_hash),
    CONSTRAINT fk_knowledge_projection_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT chk_knowledge_projection_type
        CHECK (projection_type IN ('PROBLEM', 'RESOLUTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    job_key CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    embedding_model VARCHAR(96) NOT NULL,
    embedding_dimensions INT UNSIGNED NOT NULL,
    qdrant_collection VARCHAR(128) NOT NULL,
    index_schema_version VARCHAR(24) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    max_attempts INT UNSIGNED NOT NULL DEFAULT 5,
    next_retry_at DATETIME(3) NULL,
    locked_at DATETIME(3) NULL,
    locked_by VARCHAR(96) NULL,
    last_error TEXT NULL,
    lock_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_index_job_key (job_key),
    KEY idx_knowledge_index_job_version (knowledge_unit_version_id),
    KEY idx_knowledge_index_job_claim (
        status, next_retry_at, created_at
    ),
    CONSTRAINT fk_knowledge_index_job_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT chk_knowledge_index_job_status
        CHECK (status IN (
            'PENDING', 'PROCESSING', 'COMPLETED',
            'RETRY_WAIT', 'FAILED', 'CANCELLED'
        )),
    CONSTRAINT chk_knowledge_index_job_attempts
        CHECK (attempt_count <= max_attempts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE embedding_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_index_job_id BIGINT UNSIGNED NOT NULL,
    search_projection_id BIGINT UNSIGNED NOT NULL,
    vector_name VARCHAR(40) NOT NULL,
    embedding_model VARCHAR(96) NOT NULL,
    dimensions INT UNSIGNED NOT NULL,
    embedding_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    indexed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_embedding_job_vector (
        knowledge_index_job_id, vector_name
    ),
    UNIQUE KEY uk_embedding_projection_model (
        search_projection_id, embedding_model
    ),
    CONSTRAINT fk_embedding_record_job
        FOREIGN KEY (knowledge_index_job_id)
        REFERENCES knowledge_index_job (id) ON DELETE CASCADE,
    CONSTRAINT fk_embedding_record_projection
        FOREIGN KEY (search_projection_id)
        REFERENCES knowledge_search_projection (id) ON DELETE CASCADE,
    CONSTRAINT chk_embedding_record_vector
        CHECK (vector_name IN ('problem_vector', 'resolution_vector')),
    CONSTRAINT chk_embedding_record_status
        CHECK (status IN ('GENERATED', 'INDEXED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    taxonomy_version_id BIGINT UNSIGNED NOT NULL,
    session_key CHAR(36) NOT NULL,
    parent_session_id BIGINT UNSIGNED NULL,
    stage VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    language_code VARCHAR(12) NOT NULL,
    original_query LONGTEXT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    failure_code VARCHAR(64) NULL,
    failure_message TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_session_key (session_key),
    KEY idx_diagnosis_session_cleanup (status, expires_at),
    KEY idx_diagnosis_session_parent (parent_session_id, created_at),
    CONSTRAINT fk_diagnosis_session_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_diagnosis_session_taxonomy
        FOREIGN KEY (taxonomy_version_id) REFERENCES taxonomy_version (id),
    CONSTRAINT fk_diagnosis_session_parent
        FOREIGN KEY (parent_session_id) REFERENCES diagnosis_session (id),
    CONSTRAINT chk_diagnosis_session_stage
        CHECK (stage IN ('PRE_DEPARTURE', 'ONSITE')),
    CONSTRAINT chk_diagnosis_session_status
        CHECK (status IN (
            'CREATED', 'UNDERSTANDING', 'MISSING_REQUIRED',
            'NEEDS_CLARIFICATION', 'READY_FOR_ANALYSIS',
            'ANALYZING', 'ONSITE_QUESTIONING',
            'AWAITING_CONFIRMATION', 'REANALYZING',
            'COMPLETED', 'FAILED', 'EXPIRED'
        )),
    CONSTRAINT chk_diagnosis_session_parent_stage
        CHECK (stage <> 'ONSITE' OR parent_session_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE problem_understanding (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    schema_version VARCHAR(24) NOT NULL,
    summary TEXT NULL,
    product_family VARCHAR(64) NULL,
    product_model VARCHAR(96) NULL,
    serial_number VARCHAR(128) NULL,
    component_numbers_json JSON NULL,
    error_code_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    error_codes_json JSON NOT NULL,
    symptoms_json JSON NOT NULL,
    operating_context_json JSON NULL,
    environment_context_json JSON NULL,
    extracted_fields_json JSON NOT NULL,
    completeness_json JSON NOT NULL,
    classification_status VARCHAR(32) NOT NULL,
    ready_for_analysis BOOLEAN NOT NULL DEFAULT FALSE,
    llm_model VARCHAR(96) NULL,
    prompt_version VARCHAR(40) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_problem_understanding_session (diagnosis_session_id),
    KEY idx_problem_understanding_model (
        product_model, classification_status
    ),
    CONSTRAINT fk_problem_understanding_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT chk_problem_error_state
        CHECK (error_code_state IN (
            'OBSERVED', 'NONE_CONFIRMED', 'UNKNOWN'
        )),
    CONSTRAINT chk_problem_classification_status
        CHECK (classification_status IN (
            'CLASSIFIED', 'NEEDS_CLARIFICATION',
            'MISSING_REQUIRED', 'OUT_OF_SCOPE', 'UNKNOWN'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE understanding_problem_type (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    problem_understanding_id BIGINT UNSIGNED NOT NULL,
    problem_type_id BIGINT UNSIGNED NOT NULL,
    rank_no TINYINT UNSIGNED NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    confidence DECIMAL(5,4) NOT NULL,
    matched_signals_json JSON NOT NULL,
    conflicting_signals_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_understanding_problem_type (
        problem_understanding_id, problem_type_id
    ),
    UNIQUE KEY uk_understanding_problem_rank (
        problem_understanding_id, rank_no
    ),
    CONSTRAINT fk_understanding_problem
        FOREIGN KEY (problem_understanding_id)
        REFERENCES problem_understanding (id) ON DELETE CASCADE,
    CONSTRAINT fk_understanding_problem_type
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT chk_understanding_problem_rank
        CHECK (rank_no BETWEEN 1 AND 2),
    CONSTRAINT chk_understanding_problem_primary
        CHECK (
            (rank_no = 1 AND is_primary = TRUE) OR
            (rank_no > 1 AND is_primary = FALSE)
        ),
    CONSTRAINT chk_understanding_problem_confidence
        CHECK (confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE onsite_observation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    observation_type VARCHAR(32) NOT NULL,
    signal_code VARCHAR(96) NULL,
    raw_text TEXT NOT NULL,
    value_text VARCHAR(255) NULL,
    value_number DECIMAL(18,6) NULL,
    unit VARCHAR(32) NULL,
    origin VARCHAR(24) NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_onsite_observation_session (
        diagnosis_session_id, observation_type, created_at
    ),
    CONSTRAINT fk_onsite_observation_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT chk_onsite_observation_type
        CHECK (observation_type IN (
            'TEXT', 'MEASUREMENT', 'CONFIRMATION', 'IMAGE_REFERENCE'
        )),
    CONSTRAINT chk_onsite_observation_origin
        CHECK (origin IN ('USER', 'AI_EXTRACTED', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retrieval_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    retrieval_strategy_id BIGINT UNSIGNED NOT NULL,
    parent_plan_id BIGINT UNSIGNED NULL,
    plan_key CHAR(36) NOT NULL,
    plan_revision INT UNSIGNED NOT NULL,
    stage VARCHAR(24) NOT NULL,
    plan_version VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    strategy_snapshot_json JSON NOT NULL,
    scope_json JSON NOT NULL,
    hard_filters_json JSON NOT NULL,
    steps_json JSON NOT NULL,
    grouping_rules_json JSON NOT NULL,
    ranking_config_json JSON NOT NULL,
    fallback_policy_json JSON NOT NULL,
    evidence_policy_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_retrieval_plan_key (plan_key),
    UNIQUE KEY uk_retrieval_plan_revision (
        diagnosis_session_id, plan_revision
    ),
    KEY idx_retrieval_plan_parent (parent_plan_id),
    CONSTRAINT fk_retrieval_plan_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_retrieval_plan_strategy
        FOREIGN KEY (retrieval_strategy_id) REFERENCES retrieval_strategy (id),
    CONSTRAINT fk_retrieval_plan_parent
        FOREIGN KEY (parent_plan_id) REFERENCES retrieval_plan (id),
    CONSTRAINT chk_retrieval_plan_stage
        CHECK (stage IN ('PRE_DEPARTURE', 'ONSITE')),
    CONSTRAINT chk_retrieval_plan_status
        CHECK (status IN (
            'CREATED', 'EXECUTING', 'COMPLETED', 'FAILED', 'SUPERSEDED'
        )),
    CONSTRAINT chk_retrieval_plan_revision CHECK (plan_revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE onsite_question_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    source_retrieval_plan_id BIGINT UNSIGNED NOT NULL,
    latest_retrieval_plan_id BIGINT UNSIGNED NULL,
    plan_key CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    conclusion_state VARCHAR(32) NULL,
    termination_reason VARCHAR(40) NULL,
    current_round TINYINT UNSIGNED NOT NULL DEFAULT 0,
    max_rounds TINYINT UNSIGNED NOT NULL DEFAULT 3,
    stop_reason TEXT NULL,
    state_details_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_onsite_question_plan_session (diagnosis_session_id),
    UNIQUE KEY uk_onsite_question_plan_key (plan_key),
    KEY idx_onsite_question_plan_status (status, updated_at),
    CONSTRAINT fk_onsite_question_plan_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_onsite_question_plan_source
        FOREIGN KEY (source_retrieval_plan_id) REFERENCES retrieval_plan (id),
    CONSTRAINT fk_onsite_question_plan_latest
        FOREIGN KEY (latest_retrieval_plan_id) REFERENCES retrieval_plan (id),
    CONSTRAINT chk_onsite_question_plan_status
        CHECK (status IN (
            'CREATED', 'QUESTIONING', 'AWAITING_CONFIRMATION',
            'REANALYZING', 'COMPLETED', 'FAILED'
        )),
    CONSTRAINT chk_onsite_question_plan_conclusion
        CHECK (
            conclusion_state IS NULL OR
            conclusion_state IN (
                'CONVERGED',
                'PARTIALLY_SUPPORTED',
                'INSUFFICIENT_EVIDENCE'
            )
        ),
    CONSTRAINT chk_onsite_question_plan_termination
        CHECK (
            termination_reason IS NULL OR
            termination_reason IN (
                'AUTO_CONVERGED', 'MAX_ROUNDS', 'NO_USEFUL_QUESTION',
                'KEY_INFORMATION_UNAVAILABLE', 'USER_ENDED'
            )
        ),
    CONSTRAINT chk_onsite_question_plan_rounds
        CHECK (
            max_rounds BETWEEN 1 AND 10 AND
            current_round <= max_rounds
        ),
    CONSTRAINT chk_onsite_question_plan_completion
        CHECK (
            (
                status = 'COMPLETED' AND
                conclusion_state IS NOT NULL AND
                termination_reason IS NOT NULL AND
                completed_at IS NOT NULL
            ) OR (
                status <> 'COMPLETED' AND
                conclusion_state IS NULL AND
                completed_at IS NULL
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE onsite_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    onsite_question_plan_id BIGINT UNSIGNED NOT NULL,
    source_retrieval_plan_id BIGINT UNSIGNED NOT NULL,
    cause_hypothesis_id BIGINT UNSIGNED NULL,
    round_no TINYINT UNSIGNED NOT NULL,
    target_signal_code VARCHAR(96) NOT NULL,
    target_hypotheses_json JSON NOT NULL,
    question_type VARCHAR(24) NOT NULL,
    question_zh TEXT NOT NULL,
    question_ja TEXT NULL,
    options_json JSON NULL,
    priority_score DECIMAL(5,2) NOT NULL,
    priority_features_json JSON NOT NULL,
    safety_critical BOOLEAN NOT NULL DEFAULT FALSE,
    generation_source VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    asked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    responded_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_onsite_question_round (
        onsite_question_plan_id, round_no
    ),
    KEY idx_onsite_question_signal (
        target_signal_code, status
    ),
    CONSTRAINT fk_onsite_question_plan
        FOREIGN KEY (onsite_question_plan_id)
        REFERENCES onsite_question_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_onsite_question_source_plan
        FOREIGN KEY (source_retrieval_plan_id) REFERENCES retrieval_plan (id),
    CONSTRAINT fk_onsite_question_hypothesis
        FOREIGN KEY (cause_hypothesis_id) REFERENCES cause_hypothesis (id),
    CONSTRAINT chk_onsite_question_type
        CHECK (question_type IN (
            'SINGLE_CHOICE', 'BOOLEAN', 'MEASUREMENT', 'TEXT'
        )),
    CONSTRAINT chk_onsite_question_source
        CHECK (generation_source IN (
            'SUPPORTING_SIGNAL', 'CONFLICTING_SIGNAL',
            'CLARIFICATION_TEMPLATE', 'SAFETY_CONFIRMATION'
        )),
    CONSTRAINT chk_onsite_question_status
        CHECK (status IN ('PENDING', 'RESPONDED', 'SUPERSEDED')),
    CONSTRAINT chk_onsite_question_round
        CHECK (round_no BETWEEN 1 AND 10),
    CONSTRAINT chk_onsite_question_priority
        CHECK (priority_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE onsite_question_response (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    onsite_question_id BIGINT UNSIGNED NOT NULL,
    response_type VARCHAR(24) NOT NULL,
    selected_option_code VARCHAR(64) NULL,
    raw_text TEXT NULL,
    value_text VARCHAR(255) NULL,
    value_number DECIMAL(18,6) NULL,
    unit VARCHAR(32) NULL,
    extracted_observations_json JSON NULL,
    confirmation_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_onsite_question_response (onsite_question_id),
    KEY idx_onsite_response_confirmation (
        confirmation_status, created_at
    ),
    CONSTRAINT fk_onsite_response_question
        FOREIGN KEY (onsite_question_id)
        REFERENCES onsite_question (id) ON DELETE CASCADE,
    CONSTRAINT chk_onsite_response_type
        CHECK (response_type IN (
            'OPTION', 'MEASUREMENT', 'TEXT', 'OTHER_TEXT',
            'UNAVAILABLE', 'SKIPPED'
        )),
    CONSTRAINT chk_onsite_response_confirmation
        CHECK (confirmation_status IN (
            'NOT_REQUIRED', 'PENDING', 'CONFIRMED', 'REJECTED'
        )),
    CONSTRAINT chk_onsite_response_other_confirmation
        CHECK (
            response_type <> 'OTHER_TEXT' OR
            confirmation_status IN ('PENDING', 'CONFIRMED', 'REJECTED')
        ),
    CONSTRAINT chk_onsite_response_payload
        CHECK (
            (response_type <> 'OPTION' OR selected_option_code IS NOT NULL) AND
            (response_type <> 'MEASUREMENT' OR value_number IS NOT NULL) AND
            (
                response_type NOT IN ('TEXT', 'OTHER_TEXT') OR
                raw_text IS NOT NULL
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE onsite_observation
    ADD COLUMN source_question_response_id BIGINT UNSIGNED NULL
        AFTER diagnosis_session_id,
    ADD KEY idx_onsite_observation_response (
        source_question_response_id
    ),
    ADD CONSTRAINT fk_onsite_observation_response
        FOREIGN KEY (source_question_response_id)
        REFERENCES onsite_question_response (id);

ALTER TABLE retrieval_plan
    ADD COLUMN trigger_question_response_id BIGINT UNSIGNED NULL
        AFTER parent_plan_id,
    ADD KEY idx_retrieval_plan_trigger_response (
        trigger_question_response_id
    ),
    ADD CONSTRAINT fk_retrieval_plan_trigger_response
        FOREIGN KEY (trigger_question_response_id)
        REFERENCES onsite_question_response (id);

CREATE TABLE retrieval_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    retrieval_plan_id BIGINT UNSIGNED NOT NULL,
    run_no INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    metrics_json JSON NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_retrieval_run_no (retrieval_plan_id, run_no),
    KEY idx_retrieval_run_status (status, created_at),
    CONSTRAINT fk_retrieval_run_plan
        FOREIGN KEY (retrieval_plan_id)
        REFERENCES retrieval_plan (id) ON DELETE CASCADE,
    CONSTRAINT chk_retrieval_run_status
        CHECK (status IN (
            'PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
        )),
    CONSTRAINT chk_retrieval_run_no CHECK (run_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retrieval_hit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    retrieval_run_id BIGINT UNSIGNED NOT NULL,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    search_projection_id BIGINT UNSIGNED NULL,
    hit_key CHAR(64) NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    retrieval_method VARCHAR(24) NOT NULL,
    raw_score DECIMAL(10,6) NULL,
    normalized_score DECIMAL(7,6) NULL,
    rank_no INT UNSIGNED NOT NULL,
    passed_eligibility BOOLEAN NOT NULL DEFAULT TRUE,
    exclusion_reason VARCHAR(255) NULL,
    match_features_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_retrieval_hit_key (retrieval_run_id, hit_key),
    KEY idx_retrieval_hit_rank (
        retrieval_run_id, passed_eligibility, rank_no
    ),
    KEY idx_retrieval_hit_knowledge (knowledge_unit_version_id),
    CONSTRAINT fk_retrieval_hit_run
        FOREIGN KEY (retrieval_run_id)
        REFERENCES retrieval_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_retrieval_hit_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id),
    CONSTRAINT fk_retrieval_hit_projection
        FOREIGN KEY (search_projection_id)
        REFERENCES knowledge_search_projection (id),
    CONSTRAINT chk_retrieval_hit_method
        CHECK (retrieval_method IN (
            'METADATA', 'KEYWORD', 'VECTOR', 'HYBRID'
        )),
    CONSTRAINT chk_retrieval_hit_score
        CHECK (
            normalized_score IS NULL OR
            normalized_score BETWEEN 0 AND 1
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_candidate (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    retrieval_plan_id BIGINT UNSIGNED NOT NULL,
    problem_type_id BIGINT UNSIGNED NOT NULL,
    cause_hypothesis_id BIGINT UNSIGNED NULL,
    candidate_key VARCHAR(96) NOT NULL,
    name_zh VARCHAR(160) NOT NULL,
    name_ja VARCHAR(160) NULL,
    rank_no TINYINT UNSIGNED NOT NULL,
    support_score DECIMAL(5,2) NOT NULL,
    support_band VARCHAR(32) NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    explanation TEXT NOT NULL,
    limitations_json JSON NOT NULL,
    confirmation_questions_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_candidate_key (
        retrieval_plan_id, candidate_key
    ),
    UNIQUE KEY uk_diagnosis_candidate_rank (
        retrieval_plan_id, rank_no
    ),
    KEY idx_diagnosis_candidate_session (
        diagnosis_session_id, support_band, updated_at
    ),
    KEY idx_diagnosis_candidate_problem (
        problem_type_id, support_band
    ),
    CONSTRAINT fk_diagnosis_candidate_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_candidate_plan
        FOREIGN KEY (retrieval_plan_id)
        REFERENCES retrieval_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_candidate_problem
        FOREIGN KEY (problem_type_id) REFERENCES problem_type (id),
    CONSTRAINT fk_diagnosis_candidate_hypothesis
        FOREIGN KEY (cause_hypothesis_id) REFERENCES cause_hypothesis (id),
    CONSTRAINT chk_diagnosis_candidate_rank CHECK (rank_no BETWEEN 1 AND 3),
    CONSTRAINT chk_diagnosis_candidate_score
        CHECK (support_score BETWEEN 0 AND 100),
    CONSTRAINT chk_diagnosis_candidate_band
        CHECK (support_band IN (
            'STRONG_SUPPORT', 'SUPPORTED',
            'NEEDS_CONFIRMATION', 'HIDDEN'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE candidate_evidence_link (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_candidate_id BIGINT UNSIGNED NOT NULL,
    retrieval_hit_id BIGINT UNSIGNED NOT NULL,
    knowledge_claim_id BIGINT UNSIGNED NULL,
    evidence_role VARCHAR(24) NOT NULL,
    contribution_score DECIMAL(7,6) NOT NULL,
    rationale VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate_evidence (
        diagnosis_candidate_id, retrieval_hit_id, evidence_role
    ),
    KEY idx_candidate_evidence_hit (retrieval_hit_id),
    CONSTRAINT fk_candidate_evidence_candidate
        FOREIGN KEY (diagnosis_candidate_id)
        REFERENCES diagnosis_candidate (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_evidence_hit
        FOREIGN KEY (retrieval_hit_id) REFERENCES retrieval_hit (id),
    CONSTRAINT fk_candidate_evidence_claim
        FOREIGN KEY (knowledge_claim_id) REFERENCES knowledge_claim (id),
    CONSTRAINT chk_candidate_evidence_role
        CHECK (evidence_role IN ('SUPPORTING', 'CONFLICTING', 'CONTEXT')),
    CONSTRAINT chk_candidate_evidence_score
        CHECK (contribution_score BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_recommendation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    diagnosis_candidate_id BIGINT UNSIGNED NULL,
    source_knowledge_version_id BIGINT UNSIGNED NULL,
    recommendation_type VARCHAR(24) NOT NULL,
    sequence_no SMALLINT UNSIGNED NOT NULL,
    item_code VARCHAR(96) NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    basis_type VARCHAR(32) NOT NULL,
    safety_critical BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_recommendation_sequence (
        diagnosis_session_id, recommendation_type, sequence_no
    ),
    KEY idx_diagnosis_recommendation_candidate (diagnosis_candidate_id),
    CONSTRAINT fk_diagnosis_recommendation_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_recommendation_candidate
        FOREIGN KEY (diagnosis_candidate_id)
        REFERENCES diagnosis_candidate (id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_recommendation_source
        FOREIGN KEY (source_knowledge_version_id)
        REFERENCES knowledge_unit_version (id),
    CONSTRAINT chk_diagnosis_recommendation_type
        CHECK (recommendation_type IN (
            'PART', 'TOOL', 'STEP', 'SAFETY', 'CLARIFICATION'
        )),
    CONSTRAINT chk_diagnosis_recommendation_basis
        CHECK (basis_type IN (
            'OFFICIAL_MANUAL', 'VERIFIED_CASE',
            'OBSERVED_CASE', 'USER_CONFIRMED'
        )),
    CONSTRAINT chk_diagnosis_recommendation_source
        CHECK (
            basis_type = 'USER_CONFIRMED' OR
            source_knowledge_version_id IS NOT NULL
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    report_key CHAR(36) NOT NULL,
    report_name VARCHAR(255) NOT NULL,
    note TEXT NULL,
    current_version_no INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_report_key (report_key),
    KEY idx_diagnosis_report_list (
        knowledge_base_id, status, updated_at
    ),
    CONSTRAINT fk_diagnosis_report_kb
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT chk_diagnosis_report_status
        CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_diagnosis_report_current_version
        CHECK (current_version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE diagnosis_report_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_report_id BIGINT UNSIGNED NOT NULL,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    retrieval_plan_id BIGINT UNSIGNED NOT NULL,
    taxonomy_version_id BIGINT UNSIGNED NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    report_stage VARCHAR(24) NOT NULL,
    snapshot_schema_version VARCHAR(24) NOT NULL,
    snapshot_json JSON NOT NULL,
    knowledge_index_snapshot_json JSON NOT NULL,
    generation_snapshot_json JSON NOT NULL,
    saved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagnosis_report_version (
        diagnosis_report_id, version_no
    ),
    UNIQUE KEY uk_diagnosis_report_session (diagnosis_session_id),
    KEY idx_diagnosis_report_version_session (diagnosis_session_id),
    CONSTRAINT fk_diagnosis_report_version_report
        FOREIGN KEY (diagnosis_report_id)
        REFERENCES diagnosis_report (id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_report_version_session
        FOREIGN KEY (diagnosis_session_id) REFERENCES diagnosis_session (id),
    CONSTRAINT fk_diagnosis_report_version_plan
        FOREIGN KEY (retrieval_plan_id) REFERENCES retrieval_plan (id),
    CONSTRAINT fk_diagnosis_report_version_taxonomy
        FOREIGN KEY (taxonomy_version_id) REFERENCES taxonomy_version (id),
    CONSTRAINT chk_diagnosis_report_version_stage
        CHECK (report_stage IN ('PRE_DEPARTURE', 'ONSITE')),
    CONSTRAINT chk_diagnosis_report_version_no CHECK (version_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_generation_trace (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    diagnosis_session_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    model VARCHAR(96) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    output_hash CHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    input_tokens INT UNSIGNED NULL,
    output_tokens INT UNSIGNED NULL,
    latency_ms INT UNSIGNED NULL,
    failure_message TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_generation_trace_session (
        diagnosis_session_id, purpose, created_at
    ),
    CONSTRAINT fk_ai_generation_trace_session
        FOREIGN KEY (diagnosis_session_id)
        REFERENCES diagnosis_session (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_generation_trace_purpose
        CHECK (purpose IN (
            'PROBLEM_UNDERSTANDING', 'DIAGNOSIS_EXPLANATION',
            'RECOMMENDATION_SUMMARY', 'REPORT_SUMMARY'
        )),
    CONSTRAINT chk_ai_generation_trace_status
        CHECK (status IN ('SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
