-- Per-pattern supporting cases: the historical instances the Voievod weighed
-- as evidence for (or against) a lesson. Reached only through a user-scoped
-- pattern, so no user_id here — mirrors verdict contributing-prey scoping.
-- `supported` is relative to the lesson: a case can support OR refute it.
CREATE TABLE pattern_evidence (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pattern_id      UUID NOT NULL REFERENCES patterns(id) ON DELETE CASCADE,
  symbol          TEXT NOT NULL,
  company_name    TEXT NOT NULL,
  anomaly_type    TEXT NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  supported       BOOLEAN NOT NULL,
  return_percent  NUMERIC(7,2),   -- realized return over horizon; null if open/unknown
  note            TEXT
);

CREATE INDEX idx_pattern_evidence_pattern ON pattern_evidence(pattern_id);

-- ---------------------------------------------------------------------------
-- Demo seed for the 3 PENDING patterns. Totals match the displayed counts:
--   pattern …0001 (tech spin-offs):        12 cases, 9 supported  (proposed 2026-05-14)
--   pattern …0002 (CFO insider clusters):  28 cases, 21 supported (proposed 2026-05-09)
--   pattern …0003 (quality-at-52w-low):     7 cases, 0 supported  (proposed 2026-05-15)
-- All occurred_at dates fall before the pattern's proposed_at.
-- ---------------------------------------------------------------------------

-- pattern …0001 — tech spin-offs outperform industrial spin-offs (12 cases, 9 supported)
INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type, occurred_at, supported, return_percent, note) VALUES
  ('c0000000-0000-0000-0000-000000000001','GEHC','GE HealthCare Technologies','SPINOFF','2025-12-04 00:00:00+00', true,  26.40,'Tech-heavy spin from industrial parent outperformed peer basket over 90d.'),
  ('c0000000-0000-0000-0000-000000000001','KVUE','Kenvue','SPINOFF','2025-10-22 00:00:00+00', true,  14.10,'Consumer-tech spin, steady drift above index.'),
  ('c0000000-0000-0000-0000-000000000001','SOLV','Solventum','SPINOFF','2026-01-15 00:00:00+00', true,  19.80,'Healthcare-tech carve-out beat industrial comp set.'),
  ('c0000000-0000-0000-0000-000000000001','VRNA','Veridian Analytics','SPINOFF','2025-11-09 00:00:00+00', true,  22.30,'SIC 7372 parent; classic outperformance window.'),
  ('c0000000-0000-0000-0000-000000000001','NCRV','NCR Voyix','SPINOFF','2025-10-03 00:00:00+00', true,  11.70,'Software spin separated from hardware; positive drift.'),
  ('c0000000-0000-0000-0000-000000000001','PHIN','PHINIA','SPINOFF','2025-09-18 00:00:00+00', true,  16.50,'Tech-adjacent components spin, modest outperformance.'),
  ('c0000000-0000-0000-0000-000000000001','SUNV','Sunvale Software','SPINOFF','2026-02-11 00:00:00+00', true,  28.90,'Pure SaaS spin; strongest 90d return in cohort.'),
  ('c0000000-0000-0000-0000-000000000001','ATEC','Atlas Edge Compute','SPINOFF','2025-12-19 00:00:00+00', true,  13.20,'Edge-compute spin from telecom parent.'),
  ('c0000000-0000-0000-0000-000000000001','QURO','Quoram Devices','SPINOFF','2026-01-28 00:00:00+00', true,  17.60,'SIC 7373 parent; tech spin held above benchmark.'),
  ('c0000000-0000-0000-0000-000000000001','GXO','GXO Logistics','SPINOFF','2025-08-26 00:00:00+00', false, -4.30,'Industrial-logistics spin; underperformed despite tech framing.'),
  ('c0000000-0000-0000-0000-000000000001','FOXA','Frontier Materials','SPINOFF','2025-11-30 00:00:00+00', false, -8.10,'Industrial parent, no tech tailwind; lagged index.'),
  ('c0000000-0000-0000-0000-000000000001','BRKM','Borealis Metals','SPINOFF','2026-02-02 00:00:00+00', false, -2.50,'Materials spin; flat-to-negative, counter-evidence.');

-- pattern …0002 — CFO presence in insider clusters lifts follow-through (28 cases, 21 supported)
INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type, occurred_at, supported, return_percent, note) VALUES
  ('c0000000-0000-0000-0000-000000000002','TRNS','Transcend Logic','INSIDER','2025-09-05 00:00:00+00', true,  12.40,'CFO + 2 VPs bought; strong follow-through.'),
  ('c0000000-0000-0000-0000-000000000002','MDLY','Medley Bio','INSIDER','2025-09-19 00:00:00+00', true,  18.70,'CFO led cluster ahead of trial readout.'),
  ('c0000000-0000-0000-0000-000000000002','HALO','Halcyon Power','INSIDER','2025-10-01 00:00:00+00', true,  9.30,'CFO cluster; modest but durable drift.'),
  ('c0000000-0000-0000-0000-000000000002','PVTL','Pivotal Grid','INSIDER','2025-10-14 00:00:00+00', true,  15.10,'CFO + CEO co-purchase; high follow-through.'),
  ('c0000000-0000-0000-0000-000000000002','CRDX','Cordax Health','INSIDER','2025-10-28 00:00:00+00', true,  21.60,'CFO-anchored cluster outperformed.'),
  ('c0000000-0000-0000-0000-000000000002','NVST','Novastar Energy','INSIDER','2025-11-06 00:00:00+00', true,  7.80,'CFO buy with two directors.'),
  ('c0000000-0000-0000-0000-000000000002','AXLE','Axle Robotics','INSIDER','2025-11-17 00:00:00+00', true,  24.20,'CFO cluster preceded contract win.'),
  ('c0000000-0000-0000-0000-000000000002','BRGE','Bridgepoint Fin','INSIDER','2025-11-25 00:00:00+00', true,  6.40,'CFO + treasurer; positive.'),
  ('c0000000-0000-0000-0000-000000000002','LUMN','Lumina Optics','INSIDER','2025-12-02 00:00:00+00', true,  13.90,'CFO present; clean follow-through.'),
  ('c0000000-0000-0000-0000-000000000002','STRA','Strata Cloud','INSIDER','2025-12-09 00:00:00+00', true,  17.30,'CFO-led cluster.'),
  ('c0000000-0000-0000-0000-000000000002','QNTM','Quantum Forge','INSIDER','2025-12-16 00:00:00+00', true,  10.20,'CFO buy alongside two VPs.'),
  ('c0000000-0000-0000-0000-000000000002','VELO','Velocity Auto','INSIDER','2025-12-23 00:00:00+00', true,  8.60,'CFO cluster; steady.'),
  ('c0000000-0000-0000-0000-000000000002','ORCH','Orchid Therapeutics','INSIDER','2026-01-06 00:00:00+00', true,  19.40,'CFO + chief medical officer.'),
  ('c0000000-0000-0000-0000-000000000002','FERN','Fernwood Retail','INSIDER','2026-01-13 00:00:00+00', true,  5.70,'CFO present; small win.'),
  ('c0000000-0000-0000-0000-000000000002','ZPHR','Zephyr Mobility','INSIDER','2026-01-20 00:00:00+00', true,  22.10,'CFO-anchored cluster, strong drift.'),
  ('c0000000-0000-0000-0000-000000000002','GRVT','Graviton Semi','INSIDER','2026-01-27 00:00:00+00', true,  16.80,'CFO buy ahead of guidance raise.'),
  ('c0000000-0000-0000-0000-000000000002','MAPL','Maple Ridge Bank','INSIDER','2026-02-03 00:00:00+00', true,  4.90,'CFO + two directors.'),
  ('c0000000-0000-0000-0000-000000000002','TIDE','Tidewater Marine','INSIDER','2026-02-10 00:00:00+00', true,  11.50,'CFO present; positive.'),
  ('c0000000-0000-0000-0000-000000000002','CMET','Comet Materials','INSIDER','2026-02-17 00:00:00+00', true,  14.60,'CFO-led; durable follow-through.'),
  ('c0000000-0000-0000-0000-000000000002','AURA','Aurora Devices','INSIDER','2026-02-24 00:00:00+00', true,  9.10,'CFO cluster.'),
  ('c0000000-0000-0000-0000-000000000002','NEXG','NexGen Therapeutics','INSIDER','2026-03-03 00:00:00+00', true,  20.30,'CFO + CEO co-buy.'),
  ('c0000000-0000-0000-0000-000000000002','SLDR','Solder Industrial','INSIDER','2025-09-12 00:00:00+00', false, -3.20,'CFO present but no follow-through; counter-case.'),
  ('c0000000-0000-0000-0000-000000000002','PALE','Palewood REIT','INSIDER','2025-10-21 00:00:00+00', false, -1.40,'CFO cluster fizzled.'),
  ('c0000000-0000-0000-0000-000000000002','GRTO','Greatorex Foods','INSIDER','2025-11-12 00:00:00+00', false, -5.60,'CFO buy, sector headwind erased edge.'),
  ('c0000000-0000-0000-0000-000000000002','HVST','Harvest Agritech','INSIDER','2025-12-30 00:00:00+00', false, -2.80,'CFO present; underperformed.'),
  ('c0000000-0000-0000-0000-000000000002','BLDR','Bouldercrest Mining','INSIDER','2026-01-09 00:00:00+00', false, -4.10,'CFO cluster, negative drift.'),
  ('c0000000-0000-0000-0000-000000000002','OAKS','Oakshire Telecom','INSIDER','2026-02-06 00:00:00+00', false, NULL, 'CFO buy; horizon still open, return unknown.'),
  ('c0000000-0000-0000-0000-000000000002','WNDL','Windlass Shipping','INSIDER','2026-02-19 00:00:00+00', false, -0.90,'CFO present; flat, no follow-through.');

-- pattern …0003 — quality-at-52w-low fails in declining sectors (7 cases, 0 supported)
-- The lesson is "exclude these sectors"; every case REFUTES the buy-the-dip thesis,
-- so all 7 are supported=false with negative or flat returns.
INSERT INTO pattern_evidence (pattern_id, symbol, company_name, anomaly_type, occurred_at, supported, return_percent, note) VALUES
  ('c0000000-0000-0000-0000-000000000003','GCI','Gannett Media','LAZARUS','2026-01-08 00:00:00+00', false, -18.40,'Newspaper sector; 52w-low signal failed, kept falling.'),
  ('c0000000-0000-0000-0000-000000000003','BBBY','Bedford Home Stores','LAZARUS','2026-02-12 00:00:00+00', false, -27.10,'Traditional retail; quality screen no rescue.'),
  ('c0000000-0000-0000-0000-000000000003','LUMN','Lumen Legacy Telecom','LAZARUS','2025-12-19 00:00:00+00', false, -22.60,'Legacy telecom in structural decline.'),
  ('c0000000-0000-0000-0000-000000000003','TRIB','Tribune Press Group','LAZARUS','2026-03-04 00:00:00+00', false, -15.30,'Newspaper; bounce never materialized.'),
  ('c0000000-0000-0000-0000-000000000003','SHOS','Sears Outlet Holdings','LAZARUS','2026-02-26 00:00:00+00', false, -31.50,'Declining retail; deepest drawdown in set.'),
  ('c0000000-0000-0000-0000-000000000003','FTRP','Frontier Print & Paper','LAZARUS','2026-01-22 00:00:00+00', false, -9.80,'Print media; flat-to-negative.'),
  ('c0000000-0000-0000-0000-000000000003','WIND','Windward Telephone','LAZARUS','2026-03-09 00:00:00+00', false, -12.70,'Legacy telecom; signal refuted.');
