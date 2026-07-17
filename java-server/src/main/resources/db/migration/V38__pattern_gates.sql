-- T3.3 pattern gates: machine-checkable predicate on patterns (ACTIVE + gate = enforced)
-- and scorer idempotency on pattern_evidence (outcome_ref + partial unique dedupe index).
ALTER TABLE patterns ADD COLUMN gate JSONB NULL;

ALTER TABLE pattern_evidence ADD COLUMN outcome_ref TEXT NULL;
CREATE UNIQUE INDEX idx_pattern_evidence_dedupe
  ON pattern_evidence (pattern_id, outcome_ref) WHERE outcome_ref IS NOT NULL;
