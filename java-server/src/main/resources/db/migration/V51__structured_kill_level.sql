-- V51: the structured, code-enforced kill level (spec 2026-10-02).
--
-- Hunters emit kill_criteria as free text; the executor's hard exit used to regex-parse that
-- text and never matched a single production criterion (German wording, decimal comma). The
-- code-enforced thesis-death condition is now ONE number authored by the hunter:
--   kill_close_below  a daily close strictly below this level kills the thesis (BUY only).
--
-- prey.kill_close_below            the hunter's raw value after PreyMapper's checks (null = none)
-- executor_signal.kill_close_below carried verbatim from the prey (audit: always the raw value)
-- executor_position.kill_close_below          the level the hard trigger enforces; null when the
--                                             signal had none or place-entry dropped it
-- executor_position.kill_close_below_dropped  why place-entry dropped it: 'too_tight' (closer to
--                                             the entry than 0.5 x atr_effective) or
--                                             'breached_at_adoption' (at/above the adopted
--                                             order/fill price); null = not dropped
--
-- NUMERIC(18,6) is the scale every other executor price column uses (V17). No data UPDATE here:
-- the one-time backfill of open positions is an operator step, prod data stays out of the repo.

ALTER TABLE prey              ADD COLUMN kill_close_below NUMERIC(18,6);
ALTER TABLE executor_signal   ADD COLUMN kill_close_below NUMERIC(18,6);
ALTER TABLE executor_position ADD COLUMN kill_close_below NUMERIC(18,6);
ALTER TABLE executor_position ADD COLUMN kill_close_below_dropped TEXT;
