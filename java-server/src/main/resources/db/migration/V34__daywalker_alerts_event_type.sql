-- T1.3: LLM-confirmed news event type on daywalker alerts.
-- Nullable, no backfill: only NEGATIVE_NEWS assessments from the extended
-- daywalker-assessment schema populate it; daywalker-deep never writes it
-- (same-day updates use COALESCE keep-if-null in the repository SQL).
ALTER TABLE daywalker_alerts ADD COLUMN event_type VARCHAR(32) NULL;
