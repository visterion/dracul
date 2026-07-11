-- V23: executor sim completion — scale-out ladder, MAE tracking, entry GTD, outcome log.

ALTER TABLE executor_position ADD COLUMN trim_count       INTEGER NOT NULL DEFAULT 0;
ALTER TABLE executor_position ADD COLUMN lowest_price     NUMERIC(18,6);
ALTER TABLE executor_position ADD COLUMN entry_expires_at TIMESTAMPTZ;

CREATE TABLE outcome_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                TEXT NOT NULL,              -- TRADE | COUNTERFACTUAL
    log_id_ref          TEXT NOT NULL UNIQUE,       -- decision_log.log_id of the entry/reject row
    position_id         BIGINT,                     -- TRADE only
    symbol              TEXT NOT NULL,
    reason_code         TEXT,                       -- COUNTERFACTUAL only (first failed check)
    filled              BOOLEAN,
    fill_price          NUMERIC(18,6),
    slippage_vs_limit   NUMERIC(18,6),
    holding_days        INTEGER,
    mfe_r               NUMERIC(18,6),
    mae_r               NUMERIC(18,6),
    realized_r          NUMERIC(18,6),              -- quantity-weighted over partial exits
    exit_trigger        TEXT,                       -- trigger of the FINAL exit
    exit_log_id         TEXT,
    partial_exits       JSONB,                      -- [{fraction, price, trigger, log_id}]
    reentry_within_10d  BOOLEAN,
    roundtrip_under_5d  BOOLEAN,
    hypothetical        JSONB,                      -- {r_after_20d, r_after_60d, would_have_stopped_out, skipped_reason}
    hunter_label        BOOLEAN,                    -- triple-barrier: +1R before -1R within horizon
    source_agent        TEXT,
    agent_version       TEXT,
    rule_version        TEXT,
    complete            BOOLEAN NOT NULL DEFAULT false,  -- 60d window elapsed / final exit recorded
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outcome_log_kind ON outcome_log (kind, complete);
CREATE INDEX idx_outcome_log_symbol ON outcome_log (symbol);
