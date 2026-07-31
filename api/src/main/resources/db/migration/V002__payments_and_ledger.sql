-- Payments (state machine) + double-entry ledger.
-- Invariants are enforced HERE, not only in Java: a bug, a bypass, or a manual UPDATE cannot corrupt money.

-- ============================================================================================
-- Payments
-- ============================================================================================
CREATE TABLE payments (
    id               TEXT PRIMARY KEY,
    merchant_id      TEXT        NOT NULL REFERENCES merchants (id),
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    captured_minor   BIGINT      NOT NULL DEFAULT 0,
    refunded_minor   BIGINT      NOT NULL DEFAULT 0,
    status           TEXT        NOT NULL,
    capture_method   TEXT        NOT NULL,
    description      TEXT,
    customer_email   TEXT,
    customer_ref     TEXT,
    card_brand       TEXT,
    card_last4       TEXT,
    card_fingerprint TEXT,                         -- HMAC of the test PAN; the PAN itself is never stored
    bank_ref         TEXT,
    risk_score       INTEGER,
    risk_decision    TEXT,
    failure_code     TEXT,
    failure_message  TEXT,
    checkout_token   TEXT        NOT NULL,
    success_url      TEXT,
    cancel_url       TEXT,
    metadata         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT payments_checkout_token_unique UNIQUE (checkout_token),
    CONSTRAINT payments_amount_positive       CHECK (amount_minor > 0),
    CONSTRAINT payments_currency_iso          CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payments_captured_nonneg       CHECK (captured_minor >= 0),
    CONSTRAINT payments_refunded_nonneg       CHECK (refunded_minor >= 0),
    -- The two money invariants every payments engineer must be able to recite:
    CONSTRAINT payments_captured_le_amount    CHECK (captured_minor <= amount_minor),
    CONSTRAINT payments_refunded_le_captured  CHECK (refunded_minor <= captured_minor),
    CONSTRAINT payments_capture_method_valid  CHECK (capture_method IN ('automatic', 'manual')),
    CONSTRAINT payments_status_valid          CHECK (status IN (
        'created', 'pending_bank', 'authorized', 'captured', 'partially_refunded', 'refunded', 'failed', 'canceled')),
    -- Status must agree with the money columns.
    CONSTRAINT payments_captured_matches_status CHECK (
        (status IN ('captured', 'partially_refunded', 'refunded') AND captured_minor > 0)
     OR (status NOT IN ('captured', 'partially_refunded', 'refunded') AND captured_minor = 0)),
    CONSTRAINT payments_refunded_matches_status CHECK (
        (status = 'refunded'           AND refunded_minor = captured_minor)
     OR (status = 'partially_refunded' AND refunded_minor > 0 AND refunded_minor < captured_minor)
     OR (status NOT IN ('refunded', 'partially_refunded') AND refunded_minor = 0))
);

CREATE INDEX payments_merchant_created_idx ON payments (merchant_id, id DESC);
CREATE INDEX payments_merchant_status_idx  ON payments (merchant_id, status, id DESC);
CREATE INDEX payments_status_pending_idx   ON payments (status) WHERE status = 'pending_bank';

-- Allowed transitions as DATA. The trigger below is generic; this table is the single source of truth
-- on the database side, and a test asserts it matches the Java enum.
CREATE TABLE payment_status_transitions (
    from_status TEXT NOT NULL,
    to_status   TEXT NOT NULL,
    PRIMARY KEY (from_status, to_status)
);

INSERT INTO payment_status_transitions (from_status, to_status) VALUES
    ('created',            'pending_bank'),
    ('created',            'authorized'),
    ('created',            'failed'),
    ('created',            'canceled'),
    ('pending_bank',       'authorized'),
    ('pending_bank',       'failed'),
    ('authorized',         'captured'),
    ('authorized',         'canceled'),       -- void before capture
    ('authorized',         'failed'),         -- capture declined by the bank
    ('captured',           'partially_refunded'),
    ('captured',           'refunded'),
    ('partially_refunded', 'partially_refunded'),
    ('partially_refunded', 'refunded');

CREATE OR REPLACE FUNCTION payments_enforce_transition() RETURNS trigger AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
        IF NOT EXISTS (SELECT 1 FROM payment_status_transitions t
                        WHERE t.from_status = OLD.status AND t.to_status = NEW.status) THEN
            RAISE EXCEPTION 'illegal payment transition % -> % (payment %)', OLD.status, NEW.status, OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    -- Immutable columns: amount/currency/merchant never change after creation.
    IF NEW.amount_minor <> OLD.amount_minor OR NEW.currency <> OLD.currency OR NEW.merchant_id <> OLD.merchant_id THEN
        RAISE EXCEPTION 'payment amount, currency and merchant are immutable (payment %)', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payments_transition_guard
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION payments_enforce_transition();

-- Append-only timeline shown in the dashboard.
CREATE TABLE payment_events (
    id          TEXT PRIMARY KEY,
    payment_id  TEXT        NOT NULL REFERENCES payments (id),
    type        TEXT        NOT NULL,
    from_status TEXT,
    to_status   TEXT,
    data        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_events_payment_idx ON payment_events (payment_id, id);

-- ============================================================================================
-- Ledger
-- ============================================================================================
CREATE TABLE ledger_accounts (
    id          TEXT PRIMARY KEY,
    code        TEXT        NOT NULL,                 -- e.g. bank_receivable:INR, merchant_payable:mer_x:INR
    type        TEXT        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    merchant_id TEXT        REFERENCES merchants (id), -- NULL for gateway-level accounts
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_accounts_code_unique UNIQUE (code),
    CONSTRAINT ledger_accounts_type_valid  CHECK (type IN ('asset', 'liability', 'revenue', 'expense')),
    CONSTRAINT ledger_accounts_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE journal_entries (
    id             TEXT PRIMARY KEY,
    kind           TEXT        NOT NULL,             -- capture | refund | settlement | payout | adjustment
    reference_type TEXT        NOT NULL,             -- payment | refund | settlement | payout
    reference_id   TEXT        NOT NULL,
    currency       CHAR(3)     NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    -- Posting the same business event twice is impossible: "capture of pay_X" exists at most once.
    CONSTRAINT journal_entries_unique_event UNIQUE (kind, reference_type, reference_id)
);
CREATE INDEX journal_entries_reference_idx ON journal_entries (reference_type, reference_id);

CREATE TABLE postings (
    id               TEXT PRIMARY KEY,
    journal_entry_id TEXT        NOT NULL REFERENCES journal_entries (id),
    account_id       TEXT        NOT NULL REFERENCES ledger_accounts (id),
    direction        TEXT        NOT NULL,
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT postings_direction_valid CHECK (direction IN ('debit', 'credit')),
    CONSTRAINT postings_amount_positive CHECK (amount_minor > 0)
);
CREATE INDEX postings_entry_idx   ON postings (journal_entry_id);
CREATE INDEX postings_account_idx ON postings (account_id);

-- Invariant 1: every journal entry balances (sum of debits == sum of credits), has >= 2 postings, one currency.
-- A DEFERRABLE INITIALLY DEFERRED constraint trigger runs at COMMIT, so postings can be inserted one row at a
-- time inside a transaction, but an unbalanced entry can never become visible.
CREATE OR REPLACE FUNCTION ledger_assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    v_entry_id  TEXT;
    v_balance   BIGINT;
    v_count     INTEGER;
    v_ccy_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'postings' THEN
        v_entry_id := NEW.journal_entry_id;
    ELSE
        v_entry_id := NEW.id;
    END IF;
    SELECT COALESCE(SUM(CASE WHEN direction = 'debit' THEN amount_minor ELSE -amount_minor END), 0),
           COUNT(*),
           COUNT(DISTINCT currency)
      INTO v_balance, v_count, v_ccy_count
      FROM postings WHERE journal_entry_id = v_entry_id;
    IF v_count < 2 THEN
        RAISE EXCEPTION 'journal entry % must have at least two postings (has %)', v_entry_id, v_count
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_balance <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits - credits = %', v_entry_id, v_balance
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_ccy_count <> 1 THEN
        RAISE EXCEPTION 'journal entry % mixes currencies', v_entry_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_entry_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_balanced();

-- The same check keyed from the entry side catches an entry committed with zero postings.
CREATE CONSTRAINT TRIGGER journal_entries_have_postings
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_balanced();

-- Invariant 2: a posting's currency matches its account and its journal entry.
CREATE OR REPLACE FUNCTION postings_check_currency() RETURNS trigger AS $$
DECLARE
    v_account_ccy CHAR(3);
    v_entry_ccy   CHAR(3);
BEGIN
    SELECT currency INTO v_account_ccy FROM ledger_accounts WHERE id = NEW.account_id;
    SELECT currency INTO v_entry_ccy   FROM journal_entries WHERE id = NEW.journal_entry_id;
    IF v_account_ccy IS DISTINCT FROM NEW.currency OR v_entry_ccy IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'posting currency % does not match account (%) / entry (%)', NEW.currency, v_account_ccy, v_entry_ccy
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_currency_guard
    BEFORE INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION postings_check_currency();

-- Invariant 3: the ledger is append-only. Corrections are new entries, never edits.
CREATE OR REPLACE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: ledger tables are append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER postings_append_only        BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER payment_events_append_only  BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- Balances are DERIVED. Assets/expenses carry a natural debit balance, liabilities/revenue a credit balance,
-- so "balance_minor" is positive in the account's natural direction.
CREATE VIEW account_balances AS
SELECT a.id          AS account_id,
       a.code,
       a.type,
       a.currency,
       a.merchant_id,
       CASE WHEN a.type IN ('asset', 'expense')
            THEN COALESCE(SUM(CASE WHEN p.direction = 'debit' THEN p.amount_minor ELSE -p.amount_minor END), 0)
            ELSE COALESCE(SUM(CASE WHEN p.direction = 'credit' THEN p.amount_minor ELSE -p.amount_minor END), 0)
       END           AS balance_minor,
       COUNT(p.id)   AS posting_count
FROM ledger_accounts a
LEFT JOIN postings p ON p.account_id = a.id
GROUP BY a.id, a.code, a.type, a.currency, a.merchant_id;
