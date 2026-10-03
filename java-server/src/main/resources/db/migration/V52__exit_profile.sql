-- V52: per-position exit profile for the strigoi-tech conviction basket (spec 2026-10-03 §5.1).
--
-- exit_profile            STANDARD (the classic lifecycle: chandelier trail, giveback, kill level,
--                         LLM soft exits, tranche 2) or CONVICTION (emergency stop, half sold at
--                         the target, profile trail after it, nightly catastrophe flag). Derived at
--                         place-entry from the signal mechanism (TECH_CONVICTION => CONVICTION);
--                         every existing row is STANDARD.
-- catastrophe_reason      the thesis-destroying event strigoi-tech flagged; non-null makes the hard
--                         trigger flatten the position (HARD_CATASTROPHE). Never cleared by code.
-- catastrophe_flagged_at  when it was flagged.
-- pending_trim_order_id   broker order id of an accepted partial close whose fill price is not
--                         known yet; ReconcileService resolves it (TRIM_FILL decision row).
-- broker_stop_narrow      the protective leg rests TIGHTER than the logical stop because the broker
--                         rejects a bracket leg beyond its proximity band at entry; cleared once
--                         the leg is widened to the logical stop.
--
-- No data UPDATE: the defaults are exactly the state of every pre-V52 row.

ALTER TABLE executor_position ADD COLUMN exit_profile TEXT NOT NULL DEFAULT 'STANDARD';
ALTER TABLE executor_position ADD CONSTRAINT executor_position_exit_profile_check
    CHECK (exit_profile IN ('STANDARD', 'CONVICTION'));
ALTER TABLE executor_position ADD COLUMN catastrophe_reason TEXT;
ALTER TABLE executor_position ADD COLUMN catastrophe_flagged_at TIMESTAMPTZ;
ALTER TABLE executor_position ADD COLUMN pending_trim_order_id TEXT;
ALTER TABLE executor_position ADD COLUMN broker_stop_narrow BOOLEAN NOT NULL DEFAULT false;
