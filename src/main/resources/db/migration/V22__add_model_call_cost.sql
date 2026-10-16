CREATE TABLE model_call_cost (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    call_key VARCHAR(160) NOT NULL,
    scope_key VARCHAR(64) NOT NULL,
    category VARCHAR(48) NOT NULL,
    model VARCHAR(128) NOT NULL,
    input_tokens BIGINT NULL,
    cached_tokens BIGINT NULL,
    audio_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    audio_seconds DECIMAL(20,6) NULL,
    cost_usd DECIMAL(24,12) NULL,
    status VARCHAR(32) NOT NULL,
    basis VARCHAR(24) NOT NULL,
    pricing_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_call_cost_call (call_key),
    KEY idx_model_call_cost_scope (scope_key, id)
);
