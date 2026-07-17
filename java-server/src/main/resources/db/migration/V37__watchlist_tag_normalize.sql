-- Normalizes watchlist_items.tag casing and locks it down to the two valid
-- values ('HELD' / 'TRACKING') via a CHECK constraint.
--
-- Order matters: normalize (uppercase, then defensively coerce anything
-- outside the allowed set -- including empty string -- to 'TRACKING')
-- BEFORE adding the CHECK, so pre-existing rows never violate it.
UPDATE watchlist_items SET tag = upper(tag) WHERE tag <> upper(tag);
UPDATE watchlist_items SET tag = 'TRACKING'
  WHERE upper(tag) NOT IN ('HELD','TRACKING');
ALTER TABLE watchlist_items ADD CONSTRAINT chk_watchlist_tag
  CHECK (tag IN ('HELD','TRACKING'));
