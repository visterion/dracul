-- Slice 2a: per-position risk snapshot, written by gropar's fetch_held_positions
-- and read by the morning report. All nullable: old rows + positions whose ATR
-- history is too short have no snapshot until the next gropar run. Overwritten
-- every run (the trailing stop moves) — NOT freeze-once like initial_stop.
ALTER TABLE watchlist_items
    ADD COLUMN active_stop      NUMERIC(12,4),
    ADD COLUMN next_target_2r   NUMERIC(12,4),
    ADD COLUMN current_close    NUMERIC(12,4),
    ADD COLUMN risk_snapshot_at TIMESTAMPTZ;
