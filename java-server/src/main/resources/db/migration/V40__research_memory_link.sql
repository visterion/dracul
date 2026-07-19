-- T1.6 (D9): prey-only link from a persisted entity to its HiveMem thesis cell, used solely
-- to resolve prey -> realized outcome. ref_id is TEXT because ids are heterogeneous strings
-- across kinds even though only 'prey' is written in v1 (Prey.id() is String/UUID-as-text).
CREATE TABLE research_memory_link (
  id               BIGSERIAL PRIMARY KEY,
  kind             TEXT NOT NULL,
  ref_id           TEXT NOT NULL,
  symbol           TEXT NOT NULL,
  cell_id          TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  outcome_written  BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (kind, ref_id)
);
CREATE INDEX idx_research_memory_link_unwritten
  ON research_memory_link (kind, outcome_written) WHERE kind = 'prey' AND outcome_written = false;
