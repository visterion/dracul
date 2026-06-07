-- Per-user watchlist position: editable entry price + share count.
-- Both NULL until the operator records a position for the item.
ALTER TABLE watchlist_items
  ADD COLUMN entry_price NUMERIC(12,4),
  ADD COLUMN share_count NUMERIC(12,4);
