-- Deterministic source rectangle extracted from the original PDF glyphs.
-- Coordinates use the PDF page's top-left origin and are stored with page size
-- so the browser can scale the highlight at any zoom level.
ALTER TABLE manual_knowledge_projection_v1
    ADD COLUMN source_region_json JSON NULL AFTER source_anchor;
