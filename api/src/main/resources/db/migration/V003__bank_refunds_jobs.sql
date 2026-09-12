-- Bank interaction (gateway side + simulator side), refunds, and job bookkeeping.

-- ============================================================================================ Gateway side: every call we make to the bank, keyed by a.
CREATE TABLE bank_attempts (
    id            TEXT PRIMARY KEY,
    payment_id    TEXT        NOT NULL REFERENCES payments (id),
    refund_id     TEXT,                                     -- set for refund attempts
    kind          TEXT        NOT NULL,                     -- authorize | refund
    bank_ref      TEXT        NOT NULL,
    amount_minor  BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    outcome       TEXT        NOT NULL,                     -- in_flight | approved | declined | timeout | error
    decline_code  TEXT,
    latency_ms    INTEGER,
    created_at    TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ,
    resolution    TEXT,                                     -- how a timeout was resolved: approved | declined | not_found
    CONSTRAINT bank_attempts_ref_unique UNIQUE (bank_ref),
    CONSTRAINT bank_attempts_kind_valid CHECK (kind IN ('authorize', 'refund')),
    CONSTRAINT bank_attempts_outcome_valid CHECK (outcome IN ('in_flight', 'approved', 'declined', 'timeout', 'error'))
);
CREATE INDEX bank_attempts_payment_idx ON bank_attempts (payment_id, id);

-- ============================================================================================ Refunds.
CREATE TABLE refunds (
    id            TEXT PRIMARY KEY,
    payment_id    TEXT        NOT NULL REFERENCES payments (id),
    merchant_id   TEXT        NOT NULL REFERENCES merchants (id),
    amount_minor  BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    status        TEXT        NOT NULL,                     -- pending | succeeded | failed
    reason        TEXT,
    bank_ref      TEXT,
    failure_code  TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT refunds_status_valid CHECK (status IN ('pending', 'succeeded', 'failed'))
);
CREATE INDEX refunds_payment_idx  ON refunds (payment_id, id);
CREATE INDEX refunds_merchant_idx ON refunds (merchant_id, id DESC);
CREATE INDEX refunds_pending_idx  ON refunds (status) WHERE status = 'pending';

-- A refund can only be finalized once, and only from pending.
CREATE OR REPLACE FUNCTION refunds_enforce_transition() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'pending' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'refund % is already %', OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.amount_minor <> OLD.amount_minor OR NEW.payment_id <> OLD.payment_id THEN
        RAISE EXCEPTION 'refund amount and payment are immutable (refund %)', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER refunds_transition_guard BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION refunds_enforce_transition();

-- ============================================================================================ Simulator side: "the bank's books".
CREATE TABLE banksim_transactions (
    bank_ref         TEXT PRIMARY KEY,                      -- the gateway's reference (acts as the bank's idempotency key)
    kind             TEXT        NOT NULL,                  -- authorize | refund
    parent_ref       TEXT,                                  -- refund -> the authorization it refunds
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    card_fingerprint TEXT,
    card_last4       TEXT,
    outcome          TEXT        NOT NULL,                  -- approved | declined
    decline_code     TEXT,
    auth_code        TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    settled_on       DATE,                                  -- filled by the daily settlement run
    CONSTRAINT banksim_kind_valid CHECK (kind IN ('authorize', 'refund')),
    CONSTRAINT banksim_outcome_valid CHECK (outcome IN ('approved', 'declined'))
);
CREATE INDEX banksim_transactions_settle_idx ON banksim_transactions (settled_on, created_at);

-- ============================================================================================ Background jobs: one row per run, and at most one.
CREATE TABLE job_runs (
    id          TEXT PRIMARY KEY,
    job_name    TEXT        NOT NULL,
    status      TEXT        NOT NULL,                       -- running | succeeded | failed
    trigger     TEXT        NOT NULL,                       -- internal_api | scheduler
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    result      JSONB,
    error       TEXT,
    CONSTRAINT job_runs_status_valid CHECK (status IN ('running', 'succeeded', 'failed'))
);
-- The lock: a second concurrent run of the same job cannot insert its 'running' row.
CREATE UNIQUE INDEX job_runs_one_running_idx ON job_runs (job_name) WHERE status = 'running';
CREATE INDEX job_runs_name_started_idx ON job_runs (job_name, started_at DESC);
