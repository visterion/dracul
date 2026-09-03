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
COMMENT ON COLUMN executor_position.broker_stop IS 'Price of the protective leg resting at the broker; backfilled for OPEN rows from the highest broker-confirmed stop price (never below active_stop), NULL for CLOSED/CANCELLED rows.';
COMMENT ON COLUMN executor_position.entry_filled_at IS 'First reconcile pass that saw a broker holding for this position; NULL = entry not (yet) filled.';
UPDATE executor_position p
   SET entry_filled_at = s.first_seen
  FROM (SELECT (order_json->>'position_id')::bigint AS pid, MIN(ts_decision) AS first_seen
          FROM decision_log WHERE action = 'SYNC' GROUP BY 1) s
 WHERE p.id = s.pid AND p.status = 'OPEN';

-- broker_stop backfill for OPEN rows.
--
-- The design premise "a pre-V48 leg rests at active_stop" is false for positions whose ratchet
-- only ever moved SOME of their legs: a partial ratchet confirms a new price on the moved leg but
-- deliberately leaves active_stop untouched, so such a leg can rest ABOVE active_stop (BUY). If
-- broker_stop stayed NULL, BrokerStop.forRatchet would fall back to active_stop as its monotonic
-- floor and the first permitted post-V48 ratchet could send a price BELOW where that leg already
-- rests -- a downward stop modify on a live protective leg, the one thing the design never does.
--
-- So the floor is seeded from the broker-confirmed history instead:
--   * MODIFY_STOP rows carry inputs_snapshot->>'new_stop', written only after the broker
--     confirmed the modify. They have no position_id, so they are attributed by symbol within
--     [entry_date, next same-symbol entry_date). Symbols carrying more than one OPEN position are
--     excluded from that attribution -- a row that cannot be assigned unambiguously falls back to
--     active_stop rather than seeding a floor for the wrong position.
--   * PARTIAL_TRANCHE_RATCHET rows carry their own order_json->>'position_id' and
--     order_json->>'attempted_stop', the price the moved leg was confirmed at. No join heuristic
--     is needed for them.
-- The seed is the HIGHEST confirmed price (LEAST/lowest for a SELL), never below active_stop:
-- a floor at or above where a leg actually rests can only ever produce an upward modify, whereas
-- the last confirmed price is not necessarily the highest (a later partial ratchet can carry a
-- lower chandelier, since the guard compares the chandelier to active_stop, not to the leg).
-- OPEN rows with no confirmed history get active_stop, which is exactly the old NULL fallback.
-- CLOSED/CANCELLED rows stay NULL: they have no live leg.
WITH open_pos AS (
    SELECT p.id, p.symbol, p.side, p.active_stop, p.entry_date,
           (SELECT MIN(q.entry_date) FROM executor_position q
             WHERE q.symbol = p.symbol AND q.entry_date > p.entry_date) AS window_end
      FROM executor_position p
     WHERE p.status = 'OPEN'
),
ambiguous_symbol AS (
    SELECT symbol FROM executor_position WHERE status = 'OPEN'
     GROUP BY symbol HAVING count(*) > 1
),
confirmed AS (
    SELECT o.id AS pid, (d.inputs_snapshot->>'new_stop')::numeric AS stop_price
      FROM open_pos o
      JOIN decision_log d
        ON d.symbol = o.symbol
       AND d.action = 'MODIFY_STOP'
       AND d.ts_decision >= o.entry_date
       AND (o.window_end IS NULL OR d.ts_decision < o.window_end)
     WHERE o.symbol NOT IN (SELECT symbol FROM ambiguous_symbol)
       AND d.inputs_snapshot->>'new_stop' ~ '^-?[0-9]+(\.[0-9]+)?$'
    UNION ALL
    SELECT (d.order_json->>'position_id')::bigint,
           (d.order_json->>'attempted_stop')::numeric
      FROM decision_log d
     WHERE d.reason_code = 'PARTIAL_TRANCHE_RATCHET'
       AND d.order_json->>'position_id' ~ '^[0-9]+$'
       AND d.order_json->>'attempted_stop' ~ '^-?[0-9]+(\.[0-9]+)?$'
),
seed AS (
    SELECT o.id, o.side, o.active_stop,
           CASE WHEN upper(o.side) = 'SELL' THEN min(c.stop_price) ELSE max(c.stop_price) END
               AS confirmed_stop
      FROM open_pos o
      LEFT JOIN confirmed c ON c.pid = o.id
     GROUP BY o.id, o.side, o.active_stop
)
UPDATE executor_position p
   SET broker_stop = CASE WHEN upper(s.side) = 'SELL'
                          THEN least(s.active_stop, coalesce(s.confirmed_stop, s.active_stop))
                          ELSE greatest(s.active_stop, coalesce(s.confirmed_stop, s.active_stop))
                     END
  FROM seed s
 WHERE p.id = s.id;
