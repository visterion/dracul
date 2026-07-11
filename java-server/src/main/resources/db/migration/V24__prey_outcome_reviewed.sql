-- V24: marks prey the voievod-outcome agent has already fetched/reviewed for
-- elapsed-hunt pattern extraction, so a re-run doesn't re-surface the same prey.
ALTER TABLE prey ADD COLUMN outcome_reviewed_at timestamptz;
