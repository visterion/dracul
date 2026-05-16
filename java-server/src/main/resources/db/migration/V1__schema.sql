CREATE TABLE prey (
  id               UUID PRIMARY KEY,
  symbol           TEXT NOT NULL,
  company_name     TEXT NOT NULL,
  anomaly_type     TEXT NOT NULL,
  confidence       NUMERIC(4,3) NOT NULL,
  thesis           TEXT NOT NULL,
  signals          JSONB NOT NULL DEFAULT '[]',
  risks            JSONB NOT NULL DEFAULT '[]',
  horizon          TEXT NOT NULL,
  discovered_by    TEXT NOT NULL,
  discovered_at    TIMESTAMPTZ NOT NULL,
  user_id          TEXT NOT NULL DEFAULT 'default'
);

CREATE TABLE verdicts (
  id                   UUID PRIMARY KEY,
  symbol               TEXT NOT NULL,
  company_name         TEXT NOT NULL,
  contributing_strigoi JSONB NOT NULL DEFAULT '[]',
  consensus_score      NUMERIC(4,3) NOT NULL,
  summary              TEXT NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  anomaly_types        JSONB NOT NULL DEFAULT '[]',
  current_price        NUMERIC(12,4),
  avg_confidence       NUMERIC(4,3),
  horizon              TEXT,
  signals              JSONB NOT NULL DEFAULT '[]',
  risks                JSONB NOT NULL DEFAULT '[]',
  contributing_details JSONB NOT NULL DEFAULT '[]',
  user_id              TEXT NOT NULL DEFAULT 'default'
);

CREATE TABLE patterns (
  id                  UUID PRIMARY KEY,
  applies_to_strigoi  TEXT NOT NULL,
  statement           TEXT NOT NULL,
  status              TEXT NOT NULL,
  evidence_count      INT NOT NULL,
  proposed_at         TIMESTAMPTZ NOT NULL,
  supported_count     INT,
  avg_uplift_percent  NUMERIC(6,2),
  name                TEXT,
  user_id             TEXT NOT NULL DEFAULT 'default'
);

CREATE TABLE watchlist_items (
  id                  UUID PRIMARY KEY,
  ticker              TEXT NOT NULL,
  company_name        TEXT NOT NULL,
  current_price       NUMERIC(12,4) NOT NULL,
  day_change_percent  NUMERIC(6,3) NOT NULL,
  status              TEXT NOT NULL,
  added_at            DATE NOT NULL,
  tag                 TEXT NOT NULL,
  verdict_id          UUID,
  price_history_30d   JSONB NOT NULL DEFAULT '[]',
  user_id             TEXT NOT NULL DEFAULT 'default'
);

CREATE TABLE daywalker_alerts (
  id                UUID PRIMARY KEY,
  watchlist_item_id UUID NOT NULL REFERENCES watchlist_items(id),
  at                TEXT NOT NULL,
  message           TEXT NOT NULL,
  level             TEXT NOT NULL,
  user_id           TEXT NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_prey_user_discovered  ON prey(user_id, discovered_at DESC);
CREATE INDEX idx_verdicts_user_created ON verdicts(user_id, created_at DESC);
CREATE INDEX idx_patterns_user_status  ON patterns(user_id, status);
CREATE INDEX idx_alerts_watchlist_item ON daywalker_alerts(watchlist_item_id);
