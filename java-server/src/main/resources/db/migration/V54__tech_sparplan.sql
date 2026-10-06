-- V54: Tech-Sparplan (spec 2026-10-06 §3.4, §4, §5, §7) — monthly cost-averaging adds for exit
-- profile CONVICTION. The add is its own short-lived object, never a tranche: it is never booked
-- into tranche / tranche2_* / executor_position_leg, so seeding, the leg table and the tranche-2
-- expiry stay exactly as they are.
--
-- savings_plan_month  one row per plan month (YYYY-MM). month_amount_eur / candidate_count are
--                     written by the first pass that computes them; a catch-up pass reuses them so
--                     a mid-loop failure never overspends. completed_at = every candidate has a
--                     savings_plan_buy row; missed_alerted_at = SAVINGS_PLAN_MISSED sent once.
-- savings_plan_lock   a lease row (id = 1). holder is a fresh UUID per maintenance pass (never the
--                     Vistierie run id: retries and parallel dispatch reuse it). Acquire only when
--                     until < now(); release sets until back to -infinity. The executor has no
--                     transactions, so an xact-scoped advisory lock would release at once.
-- savings_plan_carry  the not-yet-spent share per position, in account currency.
-- savings_plan_buy    one row per (month, position): the add in flight (PLACING, PLACED,
--                     CONSOLIDATING, UNPROTECTED, EMERGENCY_EXIT) or its terminal outcome
--                     (SKIPPED, REJECTED, EXPIRED, CONSOLIDATED, WINDOW_STOPPED,
--                     CLOSED_WITH_POSITION). limit_eur (the limit in account currency at placement)
--                     makes every carry refund exact. new_stop_order_id is persisted right after
--                     the consolidation place so reconcile can attribute its fill; window_stop_qty
--                     is the de-dup marker for the window-stop TRIM.
--
-- Both FKs cascade: prod never deletes positions, and test classes that wipe executor_position in
-- the shared container must not trip over a savings row.

CREATE TABLE savings_plan_month (
    month              TEXT PRIMARY KEY,
    month_amount_eur   NUMERIC(18,6),
    candidate_count    INT,
    completed_at       TIMESTAMPTZ,
    missed_alerted_at  TIMESTAMPTZ
);

CREATE TABLE savings_plan_lock (
    id      INT PRIMARY KEY CHECK (id = 1),
    holder  TEXT,
    until   TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);
INSERT INTO savings_plan_lock (id, holder, until) VALUES (1, NULL, '-infinity');

CREATE TABLE savings_plan_carry (
    position_id  BIGINT PRIMARY KEY REFERENCES executor_position(id) ON DELETE CASCADE,
    carry_eur    NUMERIC(18,6) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE savings_plan_buy (
    id                   BIGSERIAL PRIMARY KEY,
    month                TEXT NOT NULL,
    position_id          BIGINT NOT NULL REFERENCES executor_position(id) ON DELETE CASCADE,
    symbol               TEXT NOT NULL,
    qty                  NUMERIC(18,6) NOT NULL,
    limit_price          NUMERIC(18,6),
    limit_eur            NUMERIC(18,6),
    client_ref           TEXT NOT NULL UNIQUE,
    entry_order_id       TEXT,
    child_stop_order_id  TEXT,
    qty_before           NUMERIC(18,6) NOT NULL,
    avg_before           NUMERIC(18,6) NOT NULL,
    stop_before          NUMERIC(18,6) NOT NULL,
    status               TEXT NOT NULL
        CONSTRAINT savings_plan_buy_status_check
        CHECK (status IN ('PLACING', 'PLACED', 'CONSOLIDATING', 'UNPROTECTED', 'EMERGENCY_EXIT',
                          'SKIPPED', 'REJECTED', 'EXPIRED', 'CONSOLIDATED', 'WINDOW_STOPPED',
                          'CLOSED_WITH_POSITION')),
    new_stop_order_id    TEXT,
    skip_reason          TEXT,
    window_stop_qty      NUMERIC(18,6),
    target_qty           NUMERIC(18,6),
    target_stop          NUMERIC(18,6),
    fill_qty             NUMERIC(18,6),
    fill_price           NUMERIC(18,6),
    avg_after            NUMERIC(18,6),
    stop_after           NUMERIC(18,6),
    tif                  TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT savings_plan_buy_month_position_key UNIQUE (month, position_id),
    CONSTRAINT savings_plan_buy_limit_check
        CHECK (status = 'SKIPPED' OR (limit_price IS NOT NULL AND limit_eur IS NOT NULL)),
    CONSTRAINT savings_plan_buy_skip_check
        CHECK (status <> 'SKIPPED' OR (qty = 0 AND skip_reason IS NOT NULL))
);
CREATE INDEX idx_savings_plan_buy_in_flight ON savings_plan_buy (position_id)
    WHERE status IN ('PLACING', 'PLACED', 'CONSOLIDATING', 'UNPROTECTED', 'EMERGENCY_EXIT');
CREATE INDEX idx_savings_plan_buy_updated_at ON savings_plan_buy (updated_at);
