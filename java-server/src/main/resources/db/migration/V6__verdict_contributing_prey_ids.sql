-- Voievod synthesizer: the contributing prey UUIDs behind a verdict.
-- Used for upsert change-detection and future outcome analysis (Etappe 8).
ALTER TABLE verdicts
  ADD COLUMN contributing_prey_ids JSONB NOT NULL DEFAULT '[]';
