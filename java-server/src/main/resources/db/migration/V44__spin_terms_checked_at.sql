-- D3 (#45): a per-row throttle for the term-sheet capture attempt, independent of whether the
-- attempt actually wrote new terms. Since D2 (SpinTermsParser no longer extracts dates), the old
-- "due" condition (record_date IS NULL AND distribution_date IS NULL) never turns false once dates
-- are gone for good, so this column is what keeps every enrichment run from re-fetching the term
-- sheet for every DISTRIBUTED/REGISTERED/WHEN_ISSUED row. term_sheet_text is NOT touched here.
ALTER TABLE spin_candidate ADD COLUMN terms_checked_at TIMESTAMPTZ;
