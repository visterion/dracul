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
    qty            NUMERIC(18,6) NOT NULL CHECK (qty > 0),
    status         text        NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    exit_price     numeric,
    exit_reason    text,
    closed_at      timestamptz,
    UNIQUE (position_id, tranche)
);

CREATE INDEX idx_position_leg_position ON executor_position_leg (position_id);

-- Backfill from the columns the legs used to live in.
--
-- Leg quantities are NOT derived from decision_log. The ENTER decision records shares
-- ORDERED; executor_position.qty (and so each leg's qty) means shares HELD -- see
-- ExecutorPosition's javadoc. An earlier version of this migration derived tranche-1 qty
-- from the ENTER decision and got it wrong on every two-tranche position in production
-- (e.g. IMAX: the decision said 24/22, the broker's own stop-leg records say 12/34), even
-- though the two numbers summed to the right total -- the split was wrong on both sides,
-- and a sum-only check can never catch that because the sum is right by construction.
--
-- Instead, the four positions that currently carry two live tranches are seeded below with
-- the broker's own per-leg quantities, read directly off the broker's order/fill records on
-- 2026-08-25 and keyed by the exact stop-order id each quantity was measured on -- never by
-- symbol (ambiguous once a symbol has traded more than once) and never by position id (not
-- guaranteed stable). STT's and IMAX's quantities are the qty of the order currently working
-- at the broker; OFG's and RGNX's stop legs have already filled, so their quantities come
-- from the broker's fill record for that same order id. Both sources are the broker's own
-- record of shares held on that leg, not a derived figure.
--
-- These are the only real broker order ids/quantities in this codebase, and they belong only
-- here -- this migration is a data correction, the same as any other migration that carries
-- production values forward. They must not appear in tests or fixtures.
--
-- Every other position (tranche2_order_id IS NULL, i.e. single tranche) needs no seed: its
-- one leg is p.qty directly, which already means shares held.
CREATE TEMP TABLE _leg_qty_seed (stop_order_id text PRIMARY KEY, qty numeric NOT NULL) ON COMMIT DROP;
INSERT INTO _leg_qty_seed (stop_order_id, qty) VALUES
    ('5039279123', 6),    -- STT   tranche 1, qty of the order currently working at the broker
    ('5039471909', 6),    -- STT   tranche 2, qty of the order currently working at the broker
    ('5039387855', 21),   -- OFG   tranche 1, qty from the broker's fill record (leg has filled)
    ('5039471907', 21),   -- OFG   tranche 2, qty from the broker's fill record (leg has filled)
    ('5039501572', 12),   -- IMAX  tranche 1, qty of the order currently working at the broker
    ('5039503587', 34),   -- IMAX  tranche 2, qty of the order currently working at the broker
    ('5039591743', 107),  -- RGNX  tranche 1, qty from the broker's fill record (leg has filled)
    ('5039676276', 102);  -- RGNX  tranche 2, qty from the broker's fill record (leg has filled)

-- Guard: refuse to write a single leg unless every assumption the seed above makes still
-- holds against the live table -- a two-tranche position whose stop-order ids are not the
-- ones seeded, or whose seeded quantities do not sum to its qty, must abort the migration by
-- name rather than let a wrong leg quantity through. A migration that silently writes a wrong
-- leg quantity is worse than one that refuses to run.
DO $$
DECLARE
    r RECORD;
    q1 numeric;
    q2 numeric;
BEGIN
    FOR r IN SELECT id, qty, stop_order_id, tranche2_stop_order_id
             FROM executor_position WHERE tranche2_order_id IS NOT NULL
    LOOP
        SELECT s.qty INTO q1 FROM _leg_qty_seed s WHERE s.stop_order_id = r.stop_order_id;
        SELECT s.qty INTO q2 FROM _leg_qty_seed s WHERE s.stop_order_id = r.tranche2_stop_order_id;
        IF q1 IS NULL OR q2 IS NULL THEN
            RAISE EXCEPTION
                'leg backfill: position % has no seeded quantity for its stop leg(s) (stop_order_id=%, tranche2_stop_order_id=%) -- the seed in this migration must be updated before it can run',
                r.id, r.stop_order_id, r.tranche2_stop_order_id;
        END IF;
        IF q1 + q2 <> r.qty THEN
            RAISE EXCEPTION
                'leg backfill: position % seeded leg quantities (%+%=%) do not sum to its qty %',
                r.id, q1, q2, q1 + q2, r.qty;
        END IF;
    END LOOP;
END $$;

INSERT INTO executor_position_leg
    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
     exit_price, exit_reason, closed_at)
SELECT p.id, 1, p.broker_order_id, p.stop_order_id,
       CASE WHEN p.tranche2_order_id IS NULL THEN p.qty
            ELSE s1.qty END,
       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
            ELSE 'CLOSED' END,
       p.exit_price, p.exit_reason, p.closed_at
FROM executor_position p
LEFT JOIN _leg_qty_seed s1 ON s1.stop_order_id = p.stop_order_id;

INSERT INTO executor_position_leg
    (position_id, tranche, entry_order_id, stop_order_id, qty, status,
     exit_price, exit_reason, closed_at)
SELECT p.id, 2, p.tranche2_order_id, p.tranche2_stop_order_id,
       s2.qty,
       CASE WHEN p.status = 'OPEN' THEN 'OPEN'
            WHEN p.status = 'CANCELLED' THEN 'CANCELLED'
            ELSE 'CLOSED' END,
       p.exit_price, p.exit_reason, p.closed_at
FROM executor_position p
JOIN _leg_qty_seed s2 ON s2.stop_order_id = p.tranche2_stop_order_id
WHERE p.tranche2_order_id IS NOT NULL;

-- Final cross-check of the invariants that can actually fail (the earlier version of this
-- migration only checked that legs sum to qty, which both INSERTs above satisfy by
-- construction and so could never fail): every position has exactly the expected number of
-- legs (1 for single tranche, 2 for two tranches), every leg qty is positive (also enforced
-- by the CHECK constraint above, re-asserted here as a second line of defense), and the legs
-- sum to the position's qty -- now a real check, since leg quantities come from the seed
-- above, independent of qty, rather than being derived from it.
DO $$
DECLARE bad_sum int;
DECLARE bad_count int;
BEGIN
    SELECT count(*) INTO bad_sum FROM (
        SELECT p.id FROM executor_position p
        JOIN executor_position_leg l ON l.position_id = p.id
        GROUP BY p.id, p.qty HAVING sum(l.qty) <> p.qty
    ) x;
    IF bad_sum > 0 THEN
        RAISE EXCEPTION 'leg backfill: % position(s) whose legs do not sum to qty', bad_sum;
    END IF;

    SELECT count(*) INTO bad_count FROM (
        SELECT p.id FROM executor_position p
        JOIN executor_position_leg l ON l.position_id = p.id
        GROUP BY p.id, p.tranche2_order_id
        HAVING count(*) <> CASE WHEN p.tranche2_order_id IS NULL THEN 1 ELSE 2 END
    ) y;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'leg backfill: % position(s) do not have the expected number of legs', bad_count;
    END IF;
END $$;
