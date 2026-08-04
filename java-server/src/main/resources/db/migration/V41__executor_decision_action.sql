-- executor_decision rows written by submit-decision now carry the agent's action verb
-- (SKIP / HOLD / ADD_TRANCHE). Before this, only SKIP was persisted at all, so the column
-- was not needed; with HOLD and ADD_TRANCHE rows added, an audit row that is
-- accepted=false with an empty reject_reason would otherwise be ambiguous.
--
-- Nullable on purpose: the code-gate rows written on the entry/exit paths carry their
-- meaning in reject_reason and leave this column NULL, as do all pre-existing rows.
ALTER TABLE executor_decision ADD COLUMN action TEXT;
