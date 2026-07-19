-- Nullable: old signals (pre-T1.6) get no outcome cell (no backfill, per spec §7).
ALTER TABLE executor_signal ADD COLUMN prey_id TEXT;
