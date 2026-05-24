ALTER TABLE verdicts
  ADD COLUMN decision   TEXT,
  ADD COLUMN decided_at TIMESTAMPTZ;

ALTER TABLE verdicts
  ADD CONSTRAINT verdicts_decision_check
  CHECK (decision IS NULL OR decision IN ('TRACK','INTERESTING','DISMISS','ACTED'));

CREATE TABLE verdict_notes (
  id          UUID PRIMARY KEY,
  verdict_id  UUID NOT NULL REFERENCES verdicts(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  user_id     TEXT NOT NULL DEFAULT 'default'
);
CREATE INDEX idx_verdict_notes_verdict_created
  ON verdict_notes(verdict_id, created_at DESC);

CREATE UNIQUE INDEX uq_watchlist_user_ticker
  ON watchlist_items(user_id, ticker);
