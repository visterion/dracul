-- V53: strigoi-momentum (spec 2026-10-04 §3, §4, §5.3, §5.5).
--
-- executor_position.exit_profile  the CHECK learns MOMENTUM (mechanism MOMENTUM_12_1): the wide
--                                 emergency stop of CONVICTION without target-half, trail or
--                                 catastrophe flag.
-- executor_position.rebalance_exit_at
--                                 set by the strigoi-momentum completion on a held, filled
--                                 MOMENTUM position that is ranked and no longer in the final Top
--                                 10 (or left the index, or stayed unranked for a third rebalance);
--                                 HardTriggerService flattens such a row (HARD_REBALANCE). Cleared
--                                 when the name is back in the final Top 10.
-- momentum_ranking_snapshot       one row per Vistierie run (insert once): the ranking the
--                                 fetch_momentum_ranking tool computed. The completion reads ONLY
--                                 this row — it never recomputes and never trusts the LLM's echo.
--                                 month = target month (YYYY-MM), NULL when the run was not due.
-- momentum_rebalance              one row per completed target month; its existence makes the
--                                 month not due any more (catch-up, MISSED).
-- momentum_state                  key/value; 'start_month' is written by the first run so that
--                                 enabling mid-month never back-fires a rebalance.
--
-- No data UPDATE: every existing row keeps its profile, the new column is NULL.

ALTER TABLE executor_position DROP CONSTRAINT executor_position_exit_profile_check;
ALTER TABLE executor_position ADD CONSTRAINT executor_position_exit_profile_check
    CHECK (exit_profile IN ('STANDARD', 'CONVICTION', 'MOMENTUM'));
ALTER TABLE executor_position ADD COLUMN rebalance_exit_at TIMESTAMPTZ;

CREATE TABLE momentum_ranking_snapshot (
    run_id          TEXT PRIMARY KEY,
    as_of_bar_date  DATE,
    month           TEXT,
    rebalance_due   BOOLEAN NOT NULL,
    health          TEXT NOT NULL
        CONSTRAINT momentum_ranking_snapshot_health_check
        CHECK (health IN ('not_due', 'healthy', 'partial', 'unavailable')),
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_momentum_ranking_snapshot_due_month
    ON momentum_ranking_snapshot (month DESC, created_at DESC) WHERE rebalance_due;

CREATE TABLE momentum_rebalance (
    month         TEXT PRIMARY KEY,
    run_id        TEXT NOT NULL,
    completed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE momentum_state (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
