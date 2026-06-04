ALTER TABLE daywalker_alerts ADD COLUMN symbol           TEXT;
ALTER TABLE daywalker_alerts ADD COLUMN trigger_type     TEXT;
ALTER TABLE daywalker_alerts ADD COLUMN thesis           TEXT;
ALTER TABLE daywalker_alerts ADD COLUMN confidence       NUMERIC(4,3);
ALTER TABLE daywalker_alerts ADD COLUMN severity         TEXT;
ALTER TABLE daywalker_alerts ADD COLUMN vistierie_run_id TEXT;
ALTER TABLE daywalker_alerts ADD COLUMN created_at       TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_daywalker_alerts_symbol_trigger
  ON daywalker_alerts(user_id, symbol, trigger_type, created_at DESC);
