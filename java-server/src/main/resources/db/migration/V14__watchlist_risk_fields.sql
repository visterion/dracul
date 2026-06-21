-- Slice 1: per-position R-framework fields. entry_date = real purchase date
-- (backfilled to added_at); initial_stop is the frozen ATR stop in native currency.
ALTER TABLE watchlist_items
  ADD COLUMN entry_date   DATE,
  ADD COLUMN initial_stop NUMERIC(12,4);

-- Backfill existing rows to their added_at (column added without a default, so
-- existing rows are NULL here), THEN set the default so future inserts — which
-- do not mention entry_date — get CURRENT_DATE (= added_at for a new manual add).
UPDATE watchlist_items SET entry_date = added_at WHERE entry_date IS NULL;
ALTER TABLE watchlist_items ALTER COLUMN entry_date SET DEFAULT CURRENT_DATE;
