-- Webhooks via a transactional outbox.
--   outbox_events        written in the SAME transaction as the state change it describes (never lost)
--   webhook_deliveries   one row per (event, endpoint), claimed by dispatchers with FOR UPDATE SKIP LOCKED
--   webhook_delivery_attempts   append-only log of every HTTP attempt (shown in the dashboard)

CREATE TABLE webhook_endpoints (
    id             TEXT PRIMARY KEY,
    merchant_id    TEXT        NOT NULL REFERENCES merchants (id),
    url            TEXT        NOT NULL,
    secret_enc     BYTEA       NOT NULL,                    -- AES-256-GCM(APP_ENCRYPTION_KEY, whsec_...)
    enabled_events TEXT[]      NOT NULL DEFAULT '{}',       -- empty = all events
    description    TEXT,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX webhook_endpoints_merchant_idx ON webhook_endpoints (merchant_id, active);

CREATE TABLE outbox_events (
    id             TEXT PRIMARY KEY,                        -- evt_...; this is the merchant-visible event id
    merchant_id    TEXT        NOT NULL REFERENCES merchants (id),
    type           TEXT        NOT NULL,                    -- payment.captured, refund.succeeded, ...
    aggregate_type TEXT        NOT NULL,                    -- payment | refund | endpoint
    aggregate_id   TEXT        NOT NULL,
    payload        JSONB       NOT NULL,                    -- the full event body sent to endpoints
    created_at     TIMESTAMPTZ NOT NULL,
    fanned_out_at  TIMESTAMPTZ                              -- NULL until deliveries have been created
);
CREATE INDEX outbox_events_unfanned_idx ON outbox_events (id) WHERE fanned_out_at IS NULL;
CREATE INDEX outbox_events_merchant_idx ON outbox_events (merchant_id, id DESC);

CREATE TABLE webhook_deliveries (
    id               TEXT PRIMARY KEY,
    event_id         TEXT        NOT NULL REFERENCES outbox_events (id),
    endpoint_id      TEXT        NOT NULL REFERENCES webhook_endpoints (id),
    merchant_id      TEXT        NOT NULL REFERENCES merchants (id),
    status           TEXT        NOT NULL,                  -- pending | delivered | dead
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,                  -- also the lease: claiming bumps it forward
    last_status_code INTEGER,
    last_error       TEXT,
    delivered_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT webhook_deliveries_status_valid CHECK (status IN ('pending', 'delivered', 'dead')),
    CONSTRAINT webhook_deliveries_unique UNIQUE (event_id, endpoint_id)
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'pending';
CREATE INDEX webhook_deliveries_merchant_idx ON webhook_deliveries (merchant_id, id DESC);
CREATE INDEX webhook_deliveries_endpoint_idx ON webhook_deliveries (endpoint_id, id DESC);

CREATE TABLE webhook_delivery_attempts (
    id               TEXT PRIMARY KEY,
    delivery_id      TEXT        NOT NULL REFERENCES webhook_deliveries (id),
    attempt_no       INTEGER     NOT NULL,
    status_code      INTEGER,
    error            TEXT,
    duration_ms      INTEGER,
    response_snippet TEXT,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX webhook_delivery_attempts_delivery_idx ON webhook_delivery_attempts (delivery_id, attempt_no);

CREATE TRIGGER outbox_events_append_only BEFORE DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER webhook_delivery_attempts_append_only BEFORE UPDATE OR DELETE ON webhook_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
