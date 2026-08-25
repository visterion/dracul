-- One row per broker tranche. Saxo holds each tranche as its own PositionId with
-- its own stop leg, while the book models the whole position as a single row --
-- that mismatch is what made a filled stop unreconcilable (BUG-S11).
--
-- executor_position stays the aggregate view: qty remains the sum of the live
-- legs, so MAX_POSITIONS, the HEAT limit, cooldowns, outcome_log and the
-- Chronicle UI keep seeing exactly one row per symbol.
CREATE TABLE executor_position_leg (
    id             bigserial PRIMARY KEY,
    position_id    bigint      NOT NULL REFERENCES executor_position(id),
    tranche        int         NOT NULL,
    entry_order_id text,
    stop_order_id  text,
    qty            numeric     NOT NULL,
    status         text        NOT NULL,
    exit_price     numeric,
    exit_reason    text,
    closed_at      timestamptz,
    UNIQUE (position_id, tranche)
);

CREATE INDEX idx_position_leg_position ON executor_position_leg (position_id);

-- Backfill from the columns the legs used to live in. Tranche 1 always exists.
--
-- Tranche-1 quantity for a two-tranche position comes from the ENTER decision
-- joined by SIGNAL, not by symbol: joining by symbol (`d.symbol = p.symbol`)
-- silently binds the wrong ENTER row as soon as a symbol has been traded more
-- than once, because "most recent decision for this symbol" has no relation to
-- "the decision that opened THIS position". Joining on
-- `d.signal_id = p.source_signal_id` ties the decision to the exact position
-- unambiguously. Verified against the full production position book: this join
-- matches every row.
--
-- A single-tranche position never needs the decision log at all -- its tranche-1
-- qty is simply p.qty, so it cannot be affected by a missing/ambiguous ENTER row.
-- For a two-tranche position, if no matching ENTER row exists, `enter_qty` comes
-- out NULL and the INSERT below fails on the NOT NULL `qty` column -- the
-- migration aborts loudly instead of guessing (e.g. via `p.qty / 2`).
INSERT INTO executor_position_leg
    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
     exit_price, exit_reason, closed_at)
SELECT p.id, 1, p.broker_order_id, p.stop_order_id,
       CASE WHEN p.tranche2_order_id IS NULL THEN p.qty
            ELSE t1.enter_qty END,
       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
            ELSE 'CLOSED' END,
       p.exit_price, p.exit_reason, p.closed_at
FROM executor_position p
LEFT JOIN LATERAL (
    SELECT (d.order_json ->> 'qty')::numeric AS enter_qty
    FROM decision_log d
    WHERE d.action = 'ENTER' AND d.signal_id = p.source_signal_id
    ORDER BY d.ts_decision
    LIMIT 1
) t1 ON TRUE;

INSERT INTO executor_position_leg
    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
     exit_price, exit_reason, closed_at)
SELECT p.id, 2, p.tranche2_order_id, p.tranche2_stop_order_id,
       p.qty - l1.qty,
       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
            ELSE 'CLOSED' END,
       p.exit_price, p.exit_reason, p.closed_at
FROM executor_position p
JOIN executor_position_leg l1 ON l1.position_id = p.id AND l1.tranche = 1
WHERE p.tranche2_order_id IS NOT NULL;

-- Fail loudly rather than carry a wrong book forward: after the backfill every
-- position's legs must add up to its qty.
DO $$
DECLARE bad int;
BEGIN
    SELECT count(*) INTO bad FROM (
        SELECT p.id FROM executor_position p
        JOIN executor_position_leg l ON l.position_id = p.id
        GROUP BY p.id, p.qty HAVING sum(l.qty) <> p.qty
    ) x;
    IF bad > 0 THEN
        RAISE EXCEPTION 'leg backfill mismatch on % position(s)', bad;
    END IF;
END $$;
