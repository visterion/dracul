-- V10: data-driven, runtime-configurable agent definitions.
CREATE TABLE agent_definition (
  name                     TEXT PRIMARY KEY,
  model_purpose            TEXT        NOT NULL,
  prompt_text              TEXT        NOT NULL,
  output_schema            JSONB       NOT NULL,
  schedule                 TEXT,
  max_turns                INT         NOT NULL,
  max_run_seconds          INT         NOT NULL,
  completion_path          TEXT        NOT NULL,
  event_source_path        TEXT,
  session_duration_seconds INT,
  poll_interval_seconds    INT,
  enabled                  BOOLEAN     NOT NULL DEFAULT TRUE,
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_tool_binding (
  agent_name     TEXT NOT NULL REFERENCES agent_definition(name) ON DELETE CASCADE,
  tool_name      TEXT NOT NULL,
  description    TEXT,
  default_params JSONB,
  ordinal        INT  NOT NULL DEFAULT 0,
  PRIMARY KEY (agent_name, tool_name)
);

CREATE INDEX idx_agent_tool_binding_agent ON agent_tool_binding(agent_name);
