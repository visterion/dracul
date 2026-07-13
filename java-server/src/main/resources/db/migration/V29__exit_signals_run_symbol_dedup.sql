-- V29: gropar's depot-sourced exit signals never carry a watchlist_item_id (the depot
-- source has no watchlist item), so the V21 partial index uq_exit_signals_run_item
-- (vistierie_run_id, watchlist_item_id) WHERE watchlist_item_id IS NOT NULL never fires
-- for them -- webhook redelivery would insert duplicate exit_signals rows and fire
-- duplicate Telegram alerts. Add a (run, symbol) dedup key that covers this path.
-- The old index is kept -- it's still correct (and harmless) for any future source
-- that does carry a watchlist_item_id.
CREATE UNIQUE INDEX uq_exit_signals_run_symbol
  ON exit_signals (vistierie_run_id, symbol)
  WHERE vistierie_run_id IS NOT NULL;
