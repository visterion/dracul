-- Falsifiable exit conditions emitted by the hunters (executor-spec vocabulary).
-- Old rows default to [] — they are never re-emitted to the executor.
ALTER TABLE prey ADD COLUMN kill_criteria JSONB NOT NULL DEFAULT '[]';
