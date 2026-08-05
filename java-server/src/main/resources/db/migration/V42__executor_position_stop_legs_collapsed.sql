-- A trim can fold two protective stop legs into one when the remainder is smaller than the leg
-- count. Such a position is still two-tranche but legitimately has a single stop leg, and the
-- stop ratchet must not keep escalating TRANCHE_RATCHET_UNSUPPORTED for it.
ALTER TABLE executor_position
    ADD COLUMN stop_legs_collapsed boolean NOT NULL DEFAULT false;
