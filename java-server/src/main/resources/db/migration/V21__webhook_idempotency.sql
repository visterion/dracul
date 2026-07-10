-- V21: idempotent completion webhooks (review 2026-07-10, finding 4).
-- 1) Remap verdict references from duplicate prey rows to their keeper
--    (keeper = earliest discovered_at, tiebreak smallest id, per natural key).
WITH ranked AS (
  SELECT id,
         first_value(id) OVER (
           PARTITION BY symbol, anomaly_type, discovered_by, user_id,
                        ((discovered_at AT TIME ZONE 'UTC')::date)
           ORDER BY discovered_at, id
         ) AS keeper_id
  FROM prey
),
dups AS (
  SELECT id, keeper_id FROM ranked WHERE id <> keeper_id
)
UPDATE verdicts v
SET contributing_prey_ids = (
  SELECT COALESCE(jsonb_agg(DISTINCT COALESCE(d.keeper_id::text, elem)), '[]'::jsonb)
  FROM jsonb_array_elements_text(v.contributing_prey_ids) AS elem
  LEFT JOIN dups d ON d.id::text = elem
)
WHERE EXISTS (
  SELECT 1 FROM jsonb_array_elements_text(v.contributing_prey_ids) AS elem
  JOIN dups d ON d.id::text = elem
);

-- 2) Delete duplicate prey rows.
WITH ranked AS (
  SELECT id,
         first_value(id) OVER (
           PARTITION BY symbol, anomaly_type, discovered_by, user_id,
                        ((discovered_at AT TIME ZONE 'UTC')::date)
           ORDER BY discovered_at, id
         ) AS keeper_id
  FROM prey
)
DELETE FROM prey WHERE id IN (SELECT id FROM ranked WHERE id <> keeper_id);

-- 3) Enforce same-day uniqueness per hunter/user.
CREATE UNIQUE INDEX uq_prey_natural_day
  ON prey (symbol, anomaly_type, discovered_by, user_id,
           ((discovered_at AT TIME ZONE 'UTC')::date));

-- 4) One exit signal per (run, position). Both columns nullable by design;
--    rows without run id / item id are exempt.
CREATE UNIQUE INDEX uq_exit_signals_run_item
  ON exit_signals (vistierie_run_id, watchlist_item_id)
  WHERE vistierie_run_id IS NOT NULL AND watchlist_item_id IS NOT NULL;
