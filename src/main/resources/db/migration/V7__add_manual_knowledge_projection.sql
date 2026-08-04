-- Read model for service-manual knowledge used by the online diagnosis path.
-- The canonical source page and knowledge-unit version remain the source of truth.

CREATE TABLE manual_knowledge_projection_v1 (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    knowledge_unit_version_id BIGINT UNSIGNED NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    document_kind VARCHAR(40) NOT NULL,
    manufacturer VARCHAR(96) NOT NULL,
    model VARCHAR(96) NOT NULL,
    problem_type_code VARCHAR(80) NOT NULL,
    knowledge_type VARCHAR(40) NOT NULL,
    error_code VARCHAR(32) NULL,
    title VARCHAR(320) NOT NULL,
    summary LONGTEXT NOT NULL,
    action_steps_json JSON NOT NULL,
    safety_warnings_json JSON NOT NULL,
    candidate_codes_json JSON NOT NULL,
    source_reference VARCHAR(500) NOT NULL,
    pdf_page_index INT UNSIGNED NOT NULL,
    printed_page_label VARCHAR(32) NULL,
    section_path VARCHAR(160) NULL,
    problem_projection LONGTEXT NOT NULL,
    resolution_projection LONGTEXT NOT NULL,
    qdrant_point_id CHAR(36) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    indexed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_projection_knowledge_version (knowledge_unit_version_id),
    UNIQUE KEY uk_manual_projection_qdrant_point (qdrant_point_id),
    KEY idx_manual_projection_exact (
        model, problem_type_code, error_code, knowledge_type
    ),
    KEY idx_manual_projection_document (document_name, pdf_page_index),
    CONSTRAINT fk_manual_projection_knowledge_version
        FOREIGN KEY (knowledge_unit_version_id)
        REFERENCES knowledge_unit_version (id) ON DELETE CASCADE,
    CONSTRAINT chk_manual_projection_kind
        CHECK (document_kind IN ('SERVICE_MANUAL', 'PARTS_MANUAL')),
    CONSTRAINT chk_manual_projection_type
        CHECK (knowledge_type IN (
            'FAULT_DEFINITION', 'REPAIR_PROCEDURE', 'PART_REFERENCE'
        )),
    CONSTRAINT chk_manual_projection_trust
        CHECK (trust_level = 'AUTHORITATIVE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
