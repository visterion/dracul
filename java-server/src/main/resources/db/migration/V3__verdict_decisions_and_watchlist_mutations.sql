-- Add decision tracking to verdicts
ALTER TABLE verdicts
  ADD COLUMN decision   TEXT,
  ADD COLUMN decided_at TIMESTAMPTZ;

-- Add constraint for valid decision values
ALTER TABLE verdicts
  ADD CONSTRAINT verdicts_decision_check
  CHECK (decision IS NULL OR decision IN ('TRACK','INTERESTING','DISMISS','ACTED'));

-- Create verdict notes table
CREATE TABLE verdict_notes (
  id          UUID PRIMARY KEY,
  verdict_id  UUID NOT NULL REFERENCES verdicts(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  user_id     TEXT NOT NULL DEFAULT 'default'
);

-- Index for efficient querying of notes by verdict
CREATE INDEX idx_verdict_notes_verdict_created
  ON verdict_notes(verdict_id, created_at DESC);

-- Add unique constraint to watchlist_items
CREATE UNIQUE INDEX uq_watchlist_user_ticker
  ON watchlist_items(user_id, ticker);
