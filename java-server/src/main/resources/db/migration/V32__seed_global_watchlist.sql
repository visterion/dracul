-- V32: seed ~20 liquid non-US blue-chips (XETRA .DE, Tokyo .T, Hong Kong .HK) so the
-- global (EU/Asia) lazarus hunt has real names to screen additively — US watchlist
-- rows are untouched. Idempotent: ON CONFLICT (user_id, ticker) DO NOTHING matches the
-- unique index uq_watchlist_user_ticker, so re-running never overwrites a pre-existing
-- row (e.g. a user-edited SAP.DE) and never duplicates. day_change_percent seeds to 0
-- and status/tag use the neutral watchlist values ('calm' / 'tracking'); the price
-- refresher and daywalker will update them on the next live tick.

INSERT INTO watchlist_items
  (id, ticker, company_name, current_price, day_change_percent, status, added_at, tag, currency, user_id)
VALUES
  -- XETRA (.DE) — EUR
  (gen_random_uuid(), 'SAP.DE',  'SAP SE',                            140.86, 0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'SIE.DE',  'Siemens AG',                        178.0,  0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'ALV.DE',  'Allianz SE',                        330.0,  0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'BAS.DE',  'BASF SE',                           45.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'BAYN.DE', 'Bayer AG',                          27.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'DTE.DE',  'Deutsche Telekom AG',               28.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'MBG.DE',  'Mercedes-Benz Group AG',            55.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'BMW.DE',  'Bayerische Motoren Werke AG',       80.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'MUV2.DE', 'Muenchener Rueckversicherung AG',   480.0,  0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  (gen_random_uuid(), 'VOW3.DE', 'Volkswagen AG',                     95.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'EUR', 'default'),
  -- Tokyo (.T) — JPY
  (gen_random_uuid(), '7203.T',  'Toyota Motor Corp',                 2833.0, 0, 'calm', DATE '2026-07-14', 'tracking', 'JPY', 'default'),
  (gen_random_uuid(), '6758.T',  'Sony Group Corp',                   3300.0, 0, 'calm', DATE '2026-07-14', 'tracking', 'JPY', 'default'),
  (gen_random_uuid(), '9984.T',  'SoftBank Group Corp',               9500.0, 0, 'calm', DATE '2026-07-14', 'tracking', 'JPY', 'default'),
  (gen_random_uuid(), '8306.T',  'Mitsubishi UFJ Financial Group',    1900.0, 0, 'calm', DATE '2026-07-14', 'tracking', 'JPY', 'default'),
  (gen_random_uuid(), '6501.T',  'Hitachi Ltd',                       3800.0, 0, 'calm', DATE '2026-07-14', 'tracking', 'JPY', 'default'),
  -- Hong Kong (.HK) — HKD
  (gen_random_uuid(), '0700.HK', 'Tencent Holdings Ltd',              457.6,  0, 'calm', DATE '2026-07-14', 'tracking', 'HKD', 'default'),
  (gen_random_uuid(), '0941.HK', 'China Mobile Ltd',                  82.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'HKD', 'default'),
  (gen_random_uuid(), '1299.HK', 'AIA Group Ltd',                     60.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'HKD', 'default'),
  (gen_random_uuid(), '0005.HK', 'HSBC Holdings Plc',                 88.0,   0, 'calm', DATE '2026-07-14', 'tracking', 'HKD', 'default')
ON CONFLICT (user_id, ticker) DO NOTHING;
