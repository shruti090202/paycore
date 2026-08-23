-- Risk engine: rules are DATA (global defaults, per-merchant overrides), decisions are recorded per payment.

CREATE TABLE risk_rules (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        REFERENCES merchants (id),      -- NULL = global default
    type        TEXT        NOT NULL,                       -- amount_threshold | card_velocity | customer_distinct_cards | blocklist | test_card_signal
    name        TEXT        NOT NULL,
    params      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    weight      INTEGER     NOT NULL,                       -- score added when the rule fires (0-100)
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT risk_rules_weight_range CHECK (weight BETWEEN 0 AND 100)
);
-- One rule of each type per scope (NULL merchant collapses to '' so the global row is unique too).
CREATE UNIQUE INDEX risk_rules_scope_type_idx ON risk_rules (COALESCE(merchant_id, ''), type);

CREATE TABLE risk_decisions (
    id           TEXT PRIMARY KEY,
    payment_id   TEXT        NOT NULL REFERENCES payments (id),
    merchant_id  TEXT        NOT NULL REFERENCES merchants (id),
    score        INTEGER     NOT NULL,
    decision     TEXT        NOT NULL,                      -- allow | review | block
    reasons      JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- [{rule, score, message}]
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT risk_decisions_payment_unique UNIQUE (payment_id),
    CONSTRAINT risk_decisions_score_range CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT risk_decisions_decision_valid CHECK (decision IN ('allow', 'review', 'block'))
);
CREATE INDEX risk_decisions_merchant_idx ON risk_decisions (merchant_id, decision, id DESC);

CREATE TABLE blocklist (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        REFERENCES merchants (id),      -- NULL = global
    kind        TEXT        NOT NULL,                       -- card_fingerprint | email
    value_hash  TEXT        NOT NULL,                       -- fingerprint as-is, email as SHA-256(lowercase)
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT blocklist_kind_valid CHECK (kind IN ('card_fingerprint', 'email'))
);
CREATE UNIQUE INDEX blocklist_scope_value_idx ON blocklist (COALESCE(merchant_id, ''), kind, value_hash);

-- Global defaults. Amounts are minor units (INR paise): review above 10,000.00, block above 1,00,000.00.
INSERT INTO risk_rules (id, merchant_id, type, name, params, weight, enabled, created_at, updated_at) VALUES
    ('rule_global_amount',    NULL, 'amount_threshold',        'Large amount',
        '{"review_above_minor": 1000000, "block_above_minor": 10000000}', 40, TRUE, now(), now()),
    ('rule_global_velocity',  NULL, 'card_velocity',           'Card attempts per minute',
        '{"window_seconds": 60, "max_attempts": 5}', 50, TRUE, now(), now()),
    ('rule_global_cards',     NULL, 'customer_distinct_cards', 'Distinct cards per customer',
        '{"window_seconds": 3600, "max_cards": 3}', 50, TRUE, now(), now()),
    ('rule_global_blocklist', NULL, 'blocklist',               'Blocklisted card or email',
        '{}', 100, TRUE, now(), now()),
    ('rule_global_signal',    NULL, 'test_card_signal',        'Issuer risk signal (test cards)',
        '{}', 50, TRUE, now(), now());

CREATE TRIGGER risk_decisions_append_only BEFORE UPDATE OR DELETE ON risk_decisions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
