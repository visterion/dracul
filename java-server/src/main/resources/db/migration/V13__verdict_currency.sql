-- SP-2: native currency of verdicts.current_price (captured from market data at synthesis).
-- Display conversion happens at read time; this column stores the native code only.
ALTER TABLE verdicts ADD COLUMN currency TEXT;

-- Backfill: existing verdict prices were captured in the dominant native currency (USD),
-- mirroring V12's watchlist_items native backfill.
UPDATE verdicts SET currency = 'USD' WHERE current_price IS NOT NULL AND currency IS NULL;
