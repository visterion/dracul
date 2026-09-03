-- Two stops per position (SP1, spec docs/superpowers/specs/2026-09-03-executor-risk-mechanics-design.md).
--
-- active_stop keeps its meaning: the logical stop the close-based hard trigger tests and the
-- chandelier ratchet raises. broker_stop is the price actually resting at the broker -- buffered
-- below (BUY) / above (SELL) the logical stop so an intraday wick cannot close a position the
-- close-based rule would have kept. The broker leg is a catastrophe backstop, not the decision.
--
-- entry_filled_at is the tranche-2 precondition: never add to a position whose entry the broker
-- has not filled. The backfill reads decision_log SYNC rows of ANY reason code, because every
-- SYNC row proves a broker holding for that position -- ReconcileService.addSeedCandidate writes
-- a LEG_SEEDED only from a top-level WORKING protective stop, which pre-fill lives inside the
-- parent's RelatedOpenOrders, and seedLegsFromWorkingStops returns early when the broker reports
-- no position. A reason_code IN ('ENTRY_PRICE_SYNC','QTY_SYNC') predicate would miss an open
-- position whose only SYNC row is a LEG_SEEDED.
ALTER TABLE executor_position ADD COLUMN broker_stop numeric(18,6);
ALTER TABLE executor_position ADD COLUMN entry_filled_at timestamptz;
COMMENT ON COLUMN executor_position.broker_stop IS 'Price of the protective leg resting at the broker; NULL for rows opened before V48 until the next ratchet writes it.';
COMMENT ON COLUMN executor_position.entry_filled_at IS 'First reconcile pass that saw a broker holding for this position; NULL = entry not (yet) filled.';
UPDATE executor_position p
   SET entry_filled_at = s.first_seen
  FROM (SELECT (order_json->>'position_id')::bigint AS pid, MIN(ts_decision) AS first_seen
          FROM decision_log WHERE action = 'SYNC' GROUP BY 1) s
 WHERE p.id = s.pid AND p.status = 'OPEN';
