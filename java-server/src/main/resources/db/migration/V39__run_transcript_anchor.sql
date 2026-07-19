-- Schicht 1: persist the Vistierie run id on prey, and link executor signals to their prey
-- by foreign key (closes the Prey<->Signal break where signalId was UUID.randomUUID()).
-- Both columns nullable + forward-only: operator-injected signals and pre-existing rows stay NULL.

ALTER TABLE prey            ADD COLUMN run_id  TEXT;
ALTER TABLE executor_signal ADD COLUMN prey_id UUID;

CREATE INDEX idx_executor_signal_prey ON executor_signal (prey_id);
