-- Exact source text used by the evidence reader. The summary remains the
-- reviewed Chinese explanation; these fields point back to the original PDF.
ALTER TABLE manual_knowledge_projection_v1
    ADD COLUMN source_quote LONGTEXT NULL AFTER summary,
    ADD COLUMN source_anchor VARCHAR(320) NULL AFTER source_quote;
