-- Japanese business interpretation for English service-manual evidence.
-- The original PDF quote and coordinates remain unchanged and auditable.

ALTER TABLE manual_knowledge_projection_v1
    ADD COLUMN title_ja VARCHAR(320) NULL AFTER title,
    ADD COLUMN summary_ja LONGTEXT NULL AFTER summary,
    ADD COLUMN action_steps_ja_json JSON NULL AFTER action_steps_json,
    ADD COLUMN safety_warnings_ja_json JSON NULL AFTER safety_warnings_json,
    ADD COLUMN problem_projection_ja LONGTEXT NULL AFTER problem_projection,
    ADD COLUMN resolution_projection_ja LONGTEXT NULL AFTER resolution_projection;
