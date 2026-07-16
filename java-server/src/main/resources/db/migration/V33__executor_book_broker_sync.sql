-- A4.2 book-equals-broker: audit + pending-exit columns, one OPEN row per instrument.
ALTER TABLE executor_position ADD COLUMN submitted_limit_price NUMERIC(18,6);
ALTER TABLE executor_position ADD COLUMN pending_exit_reason TEXT;
ALTER TABLE executor_position ADD COLUMN exit_order_id TEXT;
ALTER TABLE executor_position ADD COLUMN exit_submitted_at TIMESTAMPTZ;
ALTER TABLE executor_position ADD COLUMN exit_price_source TEXT;
ALTER TABLE executor_position ADD COLUMN pending_exit_fill_price NUMERIC(18,6);

-- Template: uq_position_context_open (V28). Fails the migration if duplicate OPEN rows
-- exist — deliberate: duplicates need operator review, never auto-merge (spec §4.4).
CREATE UNIQUE INDEX uq_executor_position_open
  ON executor_position (connection, lower(symbol)) WHERE status = 'OPEN';
