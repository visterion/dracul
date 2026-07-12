-- V28: position_context. The research context (verdict/kill-criteria/horizon/stops)
-- that a raw broker position from the paper depot lacks. Later tasks join live depot
-- positions to this table to make depot-1 the single source of truth for held
-- positions, with this table carrying the research metadata alongside it.

CREATE TABLE position_context (
  id             TEXT PRIMARY KEY,
  connection     TEXT NOT NULL,
  symbol         TEXT NOT NULL,
  verdict_id     TEXT,
  kill_criteria  JSONB,
  horizon        TEXT,
  thesis_snapshot JSONB,
  initial_stop   NUMERIC,
  active_stop    NUMERIC,
  opened_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at      TIMESTAMPTZ,
  source         TEXT NOT NULL
);
CREATE UNIQUE INDEX uq_position_context_open
  ON position_context (connection, lower(symbol)) WHERE closed_at IS NULL;
CREATE INDEX idx_position_context_open ON position_context (connection) WHERE closed_at IS NULL;
