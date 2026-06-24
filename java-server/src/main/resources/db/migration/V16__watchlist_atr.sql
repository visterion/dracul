-- Slice 2b: persist the daily ATR gropar used, so the intraday stop-proximity
-- watcher can size the 0.5*ATR warning band without recomputing. Nullable:
-- old rows + positions with too-short ATR history have none until the next
-- gropar run. Overwritten every run alongside the 2a snapshot.
ALTER TABLE watchlist_items ADD COLUMN atr NUMERIC(12,4);
