-- Exit-side hardening: carry the triggering Prey's thesis snapshot on the signal so it can
-- flow into position_context on fill. Nullable; pre-existing signals stay thesis-less.
ALTER TABLE executor_signal ADD COLUMN thesis jsonb;
