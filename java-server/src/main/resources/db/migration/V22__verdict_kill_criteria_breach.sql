-- Deterministic kill-criteria watcher (VerdictKillCriteriaWatcher): persists which of a
-- verdict's contributing prey's kill criteria are currently breached, and when they were
-- last checked. Old rows default to [] — they are never re-emitted as newly breached.
ALTER TABLE verdicts ADD COLUMN kill_criteria_breached JSONB NOT NULL DEFAULT '[]';
ALTER TABLE verdicts ADD COLUMN kill_criteria_checked_at TIMESTAMPTZ;
