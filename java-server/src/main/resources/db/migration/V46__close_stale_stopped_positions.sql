-- Two positions were stopped out at the broker but never booked, because the fill-detection
-- path was dead until this branch fixed it (status-vocabulary mismatch). Both are long past the
-- broker's 72-hour fill-history lookback, and get_closed_positions returns {"closedPositions":[]}
-- in production for both, so the fixed code has no way left to recover them -- these values come
-- only from Saxo's own order-activity record, read on 2026-08-26:
--
--   position 7, OFG   exit_price 51.50  exit_reason HARD_STOP  closed_at 2026-08-19 19:55:37+00
--                     realized_r -0.351   r_value 4.07   exit_price_source FILL
--                     both stop legs FinalFill at 51.50: tranche 1 stop order 5039387855 qty 21,
--                     tranche 2 stop order 5039471907 qty 21
--                     R = (51.50 - 52.93) / (52.93 - 48.86) = -0.351, denominator 52.93 - 48.86 = 4.07
--                     has an ENTER decision row, so the outcome batch will produce an outcome row
--
--   position 12, RGNX exit_price 8.29   exit_reason HARD_STOP  closed_at 2026-08-24 13:30:17+00
--                     realized_r -1.13    r_value 2.3789   exit_price_source FILL
--                     both stop legs FinalFill at 8.29: tranche 1 stop order 5039591743 qty 107,
--                     tranche 2 stop order 5039676276 qty 102 -- the stop stood at 10.18, so this
--                     was a gap-down fill well below the stop
--                     R = (8.29 - 10.9889) / (10.9889 - 8.61) = -1.13, denominator 10.9889 - 8.61 = 2.3789
--                     has an ENTER decision row, so the outcome batch will produce an outcome row
--
-- r_value is the same denominator ReconcileService.computeR persists on every other close --
-- without it these two rows would be the only closed positions in the book whose realized_r can't
-- be recomputed from their own columns.
--
-- These are the only real broker order ids/prices/quantities in this codebase, and they belong
-- only here (same rule as V45) -- they must never appear in a test, fixture or comment elsewhere.
-- The FACT lines below restate the same numbers in a machine-parseable form purely so
-- CloseStaleStoppedPositionsMigrationIT / ...GuardIT can read them off this file at test setup
-- instead of retyping real broker data into test source -- do not reformat them:
--
-- FACT position id=7 symbol=OFG qty=42 exit_price=51.50 realized_r=-0.351 r_value=4.07 closed_at=2026-08-19T19:55:37Z
-- FACT leg position_id=7 tranche=1 stop_order_id=5039387855 qty=21
-- FACT leg position_id=7 tranche=2 stop_order_id=5039471907 qty=21
-- FACT position id=12 symbol=RGNX qty=209 exit_price=8.29 realized_r=-1.13 r_value=2.3789 closed_at=2026-08-24T13:30:17Z
-- FACT leg position_id=12 tranche=1 stop_order_id=5039591743 qty=107
-- FACT leg position_id=12 tranche=2 stop_order_id=5039676276 qty=102
--
-- Each position is closed, its legs are closed to match (an OPEN leg under a CLOSED position
-- would falsify the invariant V45 just established), and a cooldown is written exactly as
-- ReconcileService.bookClose does after a normal stop-out (see CooldownRepository.add): symbol,
-- exit reason, expires_at = closed_at + cooldown-days (dracul.executor.cooldown-days, default 10,
-- application.yaml), exception_condition 'fresh setup only'. expires_at is anchored on closed_at
-- (the moment the stop-out actually happened at the broker), not on the time this migration runs
-- -- that is what "exactly as the normal stop-out path does" means here: had the fill-detection
-- bug not existed, the cooldown would have been written at closed_at, not days later.
--
-- Guard: each position is identified by id AND symbol AND status = 'OPEN'. If the row is already
-- CLOSED with exactly the expected exit values, a re-run is a no-op. If it is CLOSED with anything
-- else -- or is still OPEN but its legs don't carry the exact broker-recorded stop-order-id/quantity
-- pairs above, or those legs don't sum to the position's own qty -- the migration aborts by name
-- rather than write something approximate.
--
-- A position id that does not exist at all is treated as nothing-to-do, not a failure: this
-- migration's schema history is applied to every environment (CI, local dev, a fresh Testcontainers
-- database that never carried production rows), and only production actually has ids 7/12 seeded
-- with this history. That mirrors V45's own convention (its guard only inspects rows that exist).
-- Once a row with this id exists, though, it must match -- that is where "fail loudly" applies.

DO $$
DECLARE
    pos      executor_position%ROWTYPE;
    leg_sum  numeric;
    leg1_qty numeric;
    leg2_qty numeric;
BEGIN
    -- ---------------------------------------------------------------- position 7 / OFG
    SELECT * INTO pos FROM executor_position WHERE id = 7;
    IF FOUND THEN
        IF pos.symbol <> 'OFG' THEN
            RAISE EXCEPTION 'V46: position 7 has symbol %, expected OFG', pos.symbol;
        END IF;

        IF pos.status = 'CLOSED' THEN
            IF pos.exit_price IS DISTINCT FROM 51.50
               OR pos.exit_reason IS DISTINCT FROM 'HARD_STOP'
               OR pos.exit_price_source IS DISTINCT FROM 'FILL'
               OR pos.realized_r IS DISTINCT FROM -0.351
               OR pos.r_value IS DISTINCT FROM 4.07
               OR pos.closed_at IS DISTINCT FROM TIMESTAMPTZ '2026-08-19 19:55:37+00' THEN
                RAISE EXCEPTION
                    'V46: position 7 (OFG) is already CLOSED but not with the expected exit values -- refusing to touch it (exit_price=%, exit_reason=%, exit_price_source=%, realized_r=%, r_value=%, closed_at=%)',
                    pos.exit_price, pos.exit_reason, pos.exit_price_source, pos.realized_r, pos.r_value, pos.closed_at;
            END IF;
            -- Already corrected by an earlier run of this migration -- no-op.
        ELSIF pos.status = 'OPEN' THEN
            SELECT qty INTO leg1_qty FROM executor_position_leg
                WHERE position_id = 7 AND stop_order_id = '5039387855' AND status = 'OPEN';
            SELECT qty INTO leg2_qty FROM executor_position_leg
                WHERE position_id = 7 AND stop_order_id = '5039471907' AND status = 'OPEN';
            IF leg1_qty IS NULL OR leg2_qty IS NULL THEN
                RAISE EXCEPTION
                    'V46: position 7 (OFG) does not have both expected OPEN stop legs (5039387855 qty 21, 5039471907 qty 21) -- found leg1=%, leg2=%',
                    leg1_qty, leg2_qty;
            END IF;
            IF leg1_qty <> 21 OR leg2_qty <> 21 THEN
                RAISE EXCEPTION
                    'V46: position 7 (OFG) stop legs do not carry the broker-recorded quantities (expected 21+21, found %+%)',
                    leg1_qty, leg2_qty;
            END IF;
            SELECT sum(qty) INTO leg_sum FROM executor_position_leg
                WHERE position_id = 7 AND status = 'OPEN';
            IF leg_sum <> pos.qty THEN
                RAISE EXCEPTION
                    'V46: position 7 (OFG) open legs sum to % but position qty is %', leg_sum, pos.qty;
            END IF;

            UPDATE executor_position SET
                status = 'CLOSED', exit_price = 51.50, exit_reason = 'HARD_STOP',
                exit_price_source = 'FILL', realized_r = -0.351, r_value = 4.07,
                closed_at = TIMESTAMPTZ '2026-08-19 19:55:37+00'
            WHERE id = 7 AND symbol = 'OFG' AND status = 'OPEN';

            UPDATE executor_position_leg SET
                status = 'CLOSED', exit_price = 51.50, exit_reason = 'HARD_STOP',
                closed_at = TIMESTAMPTZ '2026-08-19 19:55:37+00'
            WHERE position_id = 7 AND status = 'OPEN';

            INSERT INTO cooldown (symbol, reason, expires_at, exception_condition)
            VALUES ('OFG', 'HARD_STOP', TIMESTAMPTZ '2026-08-19 19:55:37+00' + INTERVAL '10 days',
                    'fresh setup only');
        ELSE
            RAISE EXCEPTION 'V46: position 7 (OFG) is in unexpected status % (expected OPEN or CLOSED)', pos.status;
        END IF;
    END IF;

    -- ---------------------------------------------------------------- position 12 / RGNX
    SELECT * INTO pos FROM executor_position WHERE id = 12;
    IF FOUND THEN
        IF pos.symbol <> 'RGNX' THEN
            RAISE EXCEPTION 'V46: position 12 has symbol %, expected RGNX', pos.symbol;
        END IF;

        IF pos.status = 'CLOSED' THEN
            IF pos.exit_price IS DISTINCT FROM 8.29
               OR pos.exit_reason IS DISTINCT FROM 'HARD_STOP'
               OR pos.exit_price_source IS DISTINCT FROM 'FILL'
               OR pos.realized_r IS DISTINCT FROM -1.13
               OR pos.r_value IS DISTINCT FROM 2.3789
               OR pos.closed_at IS DISTINCT FROM TIMESTAMPTZ '2026-08-24 13:30:17+00' THEN
                RAISE EXCEPTION
                    'V46: position 12 (RGNX) is already CLOSED but not with the expected exit values -- refusing to touch it (exit_price=%, exit_reason=%, exit_price_source=%, realized_r=%, r_value=%, closed_at=%)',
                    pos.exit_price, pos.exit_reason, pos.exit_price_source, pos.realized_r, pos.r_value, pos.closed_at;
            END IF;
            -- Already corrected by an earlier run of this migration -- no-op.
        ELSIF pos.status = 'OPEN' THEN
            SELECT qty INTO leg1_qty FROM executor_position_leg
                WHERE position_id = 12 AND stop_order_id = '5039591743' AND status = 'OPEN';
            SELECT qty INTO leg2_qty FROM executor_position_leg
                WHERE position_id = 12 AND stop_order_id = '5039676276' AND status = 'OPEN';
            IF leg1_qty IS NULL OR leg2_qty IS NULL THEN
                RAISE EXCEPTION
                    'V46: position 12 (RGNX) does not have both expected OPEN stop legs (5039591743 qty 107, 5039676276 qty 102) -- found leg1=%, leg2=%',
                    leg1_qty, leg2_qty;
            END IF;
            IF leg1_qty <> 107 OR leg2_qty <> 102 THEN
                RAISE EXCEPTION
                    'V46: position 12 (RGNX) stop legs do not carry the broker-recorded quantities (expected 107+102, found %+%)',
                    leg1_qty, leg2_qty;
            END IF;
            SELECT sum(qty) INTO leg_sum FROM executor_position_leg
                WHERE position_id = 12 AND status = 'OPEN';
            IF leg_sum <> pos.qty THEN
                RAISE EXCEPTION
                    'V46: position 12 (RGNX) open legs sum to % but position qty is %', leg_sum, pos.qty;
            END IF;

            UPDATE executor_position SET
                status = 'CLOSED', exit_price = 8.29, exit_reason = 'HARD_STOP',
                exit_price_source = 'FILL', realized_r = -1.13, r_value = 2.3789,
                closed_at = TIMESTAMPTZ '2026-08-24 13:30:17+00'
            WHERE id = 12 AND symbol = 'RGNX' AND status = 'OPEN';

            UPDATE executor_position_leg SET
                status = 'CLOSED', exit_price = 8.29, exit_reason = 'HARD_STOP',
                closed_at = TIMESTAMPTZ '2026-08-24 13:30:17+00'
            WHERE position_id = 12 AND status = 'OPEN';

            INSERT INTO cooldown (symbol, reason, expires_at, exception_condition)
            VALUES ('RGNX', 'HARD_STOP', TIMESTAMPTZ '2026-08-24 13:30:17+00' + INTERVAL '10 days',
                    'fresh setup only');
        ELSE
            RAISE EXCEPTION 'V46: position 12 (RGNX) is in unexpected status % (expected OPEN or CLOSED)', pos.status;
        END IF;
    END IF;
END $$;
