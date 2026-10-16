CREATE TABLE model_cost_stage (
    scope_key VARCHAR(64) NOT NULL,
    function_code VARCHAR(48) NOT NULL,
    cost_usd DECIMAL(24,12) NOT NULL,
    unpriced_calls BIGINT NOT NULL,
    estimated_calls BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope_key,function_code)
);
