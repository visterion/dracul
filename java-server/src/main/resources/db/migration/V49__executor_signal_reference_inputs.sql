-- V49: persist the two inputs an LLM_SKIP counterfactual needs.
--
-- A signal the LLM SKIPs without a place_entry writes no decision_log row, so the outcome
-- batch has no inputs_snapshot to anchor a hypothetical walk on. Rather than reconstructing
-- them (which needs an exchange-aware session calendar and still misfires on mid-session and
-- after-close prints), PreySignalEmitter now persists them at emission time, alongside
-- reference_price and under the same availability condition.
--
-- Forward-only by decision (2026-09-06): rows written before this migration keep both columns
-- NULL and are never selected by the counterfactual job.
--
-- reference_bar_date is a DATE, not a timestamp: it is the date of the last completed bar the
-- reference price came from, and a timestamp would invite a timezone shift on read.
-- reference_atr is the LONG (dracul.executor.atr-period = 22) ATR window, matching what the
-- REJECT path stores as inputs_snapshot.atr, so both counterfactual populations share one
-- ATR definition. NUMERIC(18,6) is the scale reference_price already uses (V17).

ALTER TABLE executor_signal ADD COLUMN reference_bar_date DATE;
ALTER TABLE executor_signal ADD COLUMN reference_atr      NUMERIC(18,6);
