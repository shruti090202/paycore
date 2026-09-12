-- Idempotency keys: one row per (merchant, key).
CREATE TABLE idempotency_keys (
    merchant_id           TEXT        NOT NULL REFERENCES merchants (id),
    idem_key              TEXT        NOT NULL,
    request_hash          TEXT        NOT NULL,              -- SHA-256(method + path + body)
    method                TEXT        NOT NULL,
    path                  TEXT        NOT NULL,
    status                TEXT        NOT NULL,              -- in_progress | completed
    response_status       INTEGER,
    response_content_type TEXT,
    response_body         TEXT,
    created_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, idem_key),
    CONSTRAINT idempotency_status_valid CHECK (status IN ('in_progress', 'completed')),
    CONSTRAINT idempotency_completed_has_response CHECK (status <> 'completed' OR response_status IS NOT NULL)
);
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);

-- Per-merchant rate-limit overrides (NULL = platform default from configuration).
ALTER TABLE merchants
    ADD COLUMN rate_limit_capacity   INTEGER,
    ADD COLUMN rate_limit_refill_per_second DOUBLE PRECISION;
ALTER TABLE merchants
    ADD CONSTRAINT merchants_rate_limit_valid CHECK (
        (rate_limit_capacity IS NULL OR rate_limit_capacity > 0)
    AND (rate_limit_refill_per_second IS NULL OR rate_limit_refill_per_second >= 0));
