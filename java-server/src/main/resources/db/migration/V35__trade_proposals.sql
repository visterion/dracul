-- T1.7: renfield daily watchlist review — ranked trade proposals (NO auto-trade;
-- persisted + reported only). UNIQUE (run_id, symbol) + ON CONFLICT DO NOTHING makes
-- Vistierie's up-to-3x completion-webhook retries idempotent (V21 lesson).
CREATE TABLE trade_proposals (
    id UUID PRIMARY KEY,
    owner VARCHAR NOT NULL,
    symbol VARCHAR NOT NULL,
    action VARCHAR(32) NOT NULL,
    entry_zone TEXT NULL,
    stop TEXT NULL,
    confidence NUMERIC NULL,
    rationale TEXT NOT NULL,
    market_note TEXT NULL,
    run_id VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, symbol)
);
