-- Entry completeness: sector for CONCENTRATION, entry-day high for tranche-2
-- confirmation, second bracket's order ids (ratchet must move BOTH stop legs).
ALTER TABLE executor_position ADD COLUMN sector TEXT;
ALTER TABLE executor_position ADD COLUMN entry_day_high NUMERIC(18,6);
ALTER TABLE executor_position ADD COLUMN tranche2_order_id TEXT;
ALTER TABLE executor_position ADD COLUMN tranche2_stop_order_id TEXT;
