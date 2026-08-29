-- Settlement (what the bank says it paid us) and reconciliation (does that match our books?).

CREATE TABLE settlement_files (
    id              TEXT PRIMARY KEY,
    settlement_date DATE        NOT NULL,
    row_count       INTEGER     NOT NULL,
    total_minor     BIGINT      NOT NULL,                  -- net: authorizations - refunds, as the bank computes it
    currency        CHAR(3)     NOT NULL,
    csv             TEXT        NOT NULL,                  -- the file as received (kept verbatim for audit)
    generated_at    TIMESTAMPTZ NOT NULL                   -- several files per day are fine (on-demand runs)
);
CREATE INDEX settlement_files_date_idx ON settlement_files (settlement_date, currency);

CREATE TABLE reconciliation_runs (
    id                 TEXT PRIMARY KEY,
    settlement_file_id TEXT        NOT NULL REFERENCES settlement_files (id),
    status             TEXT        NOT NULL,               -- running | completed | failed
    rows_total         INTEGER     NOT NULL DEFAULT 0,
    rows_matched       INTEGER     NOT NULL DEFAULT 0,
    items_open         INTEGER     NOT NULL DEFAULT 0,
    settled_minor      BIGINT      NOT NULL DEFAULT 0,     -- amount actually booked to settlement_cash
    journal_entry_id   TEXT,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    error              TEXT,
    CONSTRAINT reconciliation_runs_file_unique UNIQUE (settlement_file_id),
    CONSTRAINT reconciliation_runs_status_valid CHECK (status IN ('running', 'completed', 'failed'))
);

CREATE TABLE reconciliation_items (
    id             TEXT PRIMARY KEY,
    run_id         TEXT        NOT NULL REFERENCES reconciliation_runs (id),
    merchant_id    TEXT        REFERENCES merchants (id),
    kind           TEXT        NOT NULL,                   -- missing_in_ledger | missing_in_bank | amount_mismatch | duplicate
    bank_ref       TEXT,
    payment_id     TEXT,
    refund_id      TEXT,
    expected_minor BIGINT,                                 -- what our books say
    actual_minor   BIGINT,                                 -- what the bank file says
    detail         TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'open',    -- open | resolved
    resolution     TEXT,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT reconciliation_items_kind_valid CHECK (kind IN ('missing_in_ledger', 'missing_in_bank', 'amount_mismatch', 'duplicate')),
    CONSTRAINT reconciliation_items_status_valid CHECK (status IN ('open', 'resolved'))
);
CREATE INDEX reconciliation_items_run_idx ON reconciliation_items (run_id, id);
CREATE INDEX reconciliation_items_merchant_idx ON reconciliation_items (merchant_id, status, id DESC);

CREATE TABLE payouts (
    id               TEXT PRIMARY KEY,
    merchant_id      TEXT        NOT NULL REFERENCES merchants (id),
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL,                 -- paid (simulated instant transfer)
    journal_entry_id TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT payouts_amount_positive CHECK (amount_minor > 0)
);
CREATE INDEX payouts_merchant_idx ON payouts (merchant_id, id DESC);

-- Retention policy needs to prune old rows; history stays immutable (UPDATE still rejected on attempts).
DROP TRIGGER IF EXISTS outbox_events_append_only ON outbox_events;
DROP TRIGGER IF EXISTS webhook_delivery_attempts_append_only ON webhook_delivery_attempts;
CREATE TRIGGER webhook_delivery_attempts_no_update BEFORE UPDATE ON webhook_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- Which settlement file (if any) reported this bank call. NULL + old + approved = "missing in bank".
ALTER TABLE bank_attempts ADD COLUMN settled_in TEXT REFERENCES settlement_files (id);
CREATE INDEX bank_attempts_unsettled_idx ON bank_attempts (created_at) WHERE settled_in IS NULL AND outcome = 'approved';
