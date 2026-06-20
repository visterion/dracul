-- V12: per-item native currency + entry currency for the watchlist; default display currency.
ALTER TABLE watchlist_items ADD COLUMN currency       TEXT;
ALTER TABLE watchlist_items ADD COLUMN entry_currency TEXT;

-- Backfill: existing live prices are US-market native (USD); existing entry prices were
-- entered believing the (default) display currency EUR.
UPDATE watchlist_items SET currency = 'USD' WHERE currency IS NULL;
UPDATE watchlist_items SET entry_currency = 'EUR' WHERE entry_currency IS NULL AND entry_price IS NOT NULL;

-- Instance display currency (app_settings already exists from V7).
INSERT INTO app_settings (key, value) VALUES ('display_currency', 'EUR')
ON CONFLICT (key) DO NOTHING;
