-- V11: advisory exit signals emitted by the gropar agent.
CREATE TABLE exit_signals (
  id                UUID PRIMARY KEY,
  watchlist_item_id UUID REFERENCES watchlist_items(id) ON DELETE CASCADE,
  symbol            TEXT NOT NULL,
  action            TEXT NOT NULL,                 -- SELL | TRIM | HOLD
  fired_rules       JSONB NOT NULL DEFAULT '[]',
  gain_loss_pct     NUMERIC(8,3),
  thesis_status     TEXT,                          -- INTACT | WEAKENING | INVALIDATED
  rationale         TEXT NOT NULL,
  confidence        NUMERIC(4,3),
  vistierie_run_id  TEXT,
  run_at            TEXT NOT NULL,
  user_id           TEXT NOT NULL DEFAULT 'default',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_exit_signals_item ON exit_signals(watchlist_item_id);
CREATE INDEX idx_exit_signals_user_created ON exit_signals(user_id, created_at DESC);
