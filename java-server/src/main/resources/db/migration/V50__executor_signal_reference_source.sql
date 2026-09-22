-- V50: record WHERE a signal's counterfactual anchors came from.
--
-- SP12 (2026-09-22) reconstructs reference_bar_date/reference_atr for signals emitted before V49
-- from today's daily history, so they can be walked. Those values must stay distinguishable from
-- the ones PreySignalEmitter persisted at emission time:
--   emission          written by PreySignalEmitter at emission (V49 semantics)
--   reconstructed     written by the nightly OutcomeBatchJob anchor-reconstruction step
--   unreconstructable the step tried and the failure is permanent (not retried; reset to NULL to re-queue)
--   manual            anchors present but dated AFTER the emission day: hand-written, no code path
--                     produces that (one operator signal in prod at migration time)
-- NULL = no anchors and not yet attempted.

ALTER TABLE executor_signal ADD COLUMN reference_source VARCHAR(20);
ALTER TABLE executor_signal ADD CONSTRAINT executor_signal_reference_source_chk
  CHECK (reference_source IN ('emission','reconstructed','unreconstructable','manual'));

UPDATE executor_signal SET reference_source = 'emission'
 WHERE reference_bar_date IS NOT NULL AND reference_atr IS NOT NULL
   AND reference_bar_date <= (created_at AT TIME ZONE 'UTC')::date;

UPDATE executor_signal SET reference_source = 'manual'
 WHERE reference_bar_date IS NOT NULL AND reference_atr IS NOT NULL
   AND reference_bar_date > (created_at AT TIME ZONE 'UTC')::date;
