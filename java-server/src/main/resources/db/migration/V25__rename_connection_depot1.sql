-- Neutral connection naming: the executor agent must not be able to infer
-- broker or paper/live from the connection id. Agora renames its connection
-- key saxo-sim -> depot-1; this keeps persisted open positions matching
-- (reconciliation and OrderGuard compare executor_position.connection against
-- the configured connection string).
UPDATE executor_position SET connection = 'depot-1' WHERE connection = 'saxo-sim';
