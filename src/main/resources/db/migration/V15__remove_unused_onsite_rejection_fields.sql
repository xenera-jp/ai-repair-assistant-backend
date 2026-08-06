-- MySQL checks index dependencies before processing a multi-action ALTER.
-- Remove the foreign key first, then remove its supporting index separately.
ALTER TABLE onsite_rejection_v1
    DROP FOREIGN KEY fk_onsite_rejection_rejected;

ALTER TABLE onsite_rejection_v1
    DROP CHECK chk_onsite_rejection_scope;

ALTER TABLE onsite_rejection_v1
    DROP INDEX idx_onsite_rejection_rejected;

ALTER TABLE onsite_rejection_v1
    DROP COLUMN scope,
    DROP COLUMN rejected_candidate_codes,
    DROP COLUMN reason_code,
    DROP COLUMN reason_text;
