-- V30: depot-sourced Daywalker alerts (A6) carry a symbol, not a watchlist_item_id --
-- DaywalkerEventEngine now fans triggers over depot positions (2cdb514), so every
-- position_id riding through DaywalkerCompletionService.persistAssessment is a ticker
-- symbol, never a watchlist_items UUID. watchlist_item_id must become optional so
-- depot-sourced alerts (which have no watchlist row at all) can persist; existing
-- watch-only alert rows keep their watchlist_item_id unchanged.
ALTER TABLE daywalker_alerts DROP CONSTRAINT IF EXISTS daywalker_alerts_watchlist_item_id_fkey;
ALTER TABLE daywalker_alerts ALTER COLUMN watchlist_item_id DROP NOT NULL;
ALTER TABLE daywalker_alerts ADD CONSTRAINT daywalker_alerts_watchlist_item_id_fkey
  FOREIGN KEY (watchlist_item_id) REFERENCES watchlist_items(id);
