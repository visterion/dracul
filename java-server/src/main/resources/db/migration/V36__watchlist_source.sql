-- V36: add watchlist_items.source (provenance: 'verdict' | 'seed' | 'manual') and
-- backfill it. No user_id constraint by design (verified against prod): verdict
-- beats seed, and the V32 non-US seed rows are identified by their fixed
-- added_at date + the fixed seed ticker list.

ALTER TABLE watchlist_items ADD COLUMN source TEXT NOT NULL DEFAULT 'manual';

UPDATE watchlist_items SET source = 'verdict' WHERE verdict_id IS NOT NULL;

UPDATE watchlist_items SET source = 'seed'
WHERE verdict_id IS NULL
  AND added_at = DATE '2026-07-14'
  AND ticker IN ('SAP.DE','SIE.DE','ALV.DE','BAS.DE','BAYN.DE','DTE.DE','MBG.DE',
                 'BMW.DE','MUV2.DE','VOW3.DE','7203.T','6758.T','9984.T','8306.T',
                 '6501.T','0700.HK','0941.HK','1299.HK','0005.HK');
