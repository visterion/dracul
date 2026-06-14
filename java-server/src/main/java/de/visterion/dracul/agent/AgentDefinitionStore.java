package de.visterion.dracul.agent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentDefinitionStore {

    private record BaseRow(String name, String modelPurpose, String promptText,
                           String outputSchema, String schedule, int maxTurns,
                           int maxRunSeconds, String completionPath, String eventSourcePath,
                           Integer sessionDurationSeconds, Integer pollIntervalSeconds,
                           boolean enabled) {}

    private static BaseRow mapBaseRow(ResultSet rs, int rowNum) throws SQLException {
        return new BaseRow(
                rs.getString("name"), rs.getString("model_purpose"),
                rs.getString("prompt_text"), rs.getString("output_schema"),
                rs.getString("schedule"), rs.getInt("max_turns"),
                rs.getInt("max_run_seconds"), rs.getString("completion_path"),
                rs.getString("event_source_path"),
                (Integer) rs.getObject("session_duration_seconds"),
                (Integer) rs.getObject("poll_interval_seconds"),
                rs.getBoolean("enabled"));
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AgentDefinitionStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Insert only if the row is absent. Returns true if it inserted. */
    public boolean insertIfAbsent(AgentDefinition d) {
        int rows = jdbc.sql("""
                INSERT INTO agent_definition
                  (name, model_purpose, prompt_text, output_schema, schedule,
                   max_turns, max_run_seconds, completion_path, event_source_path,
                   session_duration_seconds, poll_interval_seconds, enabled, updated_at)
                VALUES
                  (:name, :mp, :prompt, CAST(:schema AS jsonb), :schedule,
                   :turns, :secs, :completion, :esp, :sds, :pis, :enabled, now())
                ON CONFLICT (name) DO NOTHING
                """)
                .param("name", d.name()).param("mp", d.modelPurpose())
                .param("prompt", d.promptText()).param("schema", d.outputSchema().toString())
                .param("schedule", d.schedule()).param("turns", d.maxTurns())
                .param("secs", d.maxRunSeconds()).param("completion", d.completionPath())
                .param("esp", d.eventSourcePath()).param("sds", d.sessionDurationSeconds())
                .param("pis", d.pollIntervalSeconds()).param("enabled", d.enabled())
                .update();
        if (rows > 0) replaceTools(d);
        return rows > 0;
    }

    /** Updates an existing definition's editable fields and replaces its tool
     *  bindings. Requires the row to exist (callers ensure this via bootstrap /
     *  existence check); a no-op if the name is absent. */
    public void save(AgentDefinition d) {
        jdbc.sql("""
                UPDATE agent_definition SET
                  model_purpose = :mp, prompt_text = :prompt,
                  output_schema = CAST(:schema AS jsonb), schedule = :schedule,
                  max_turns = :turns, max_run_seconds = :secs,
                  completion_path = :completion, event_source_path = :esp,
                  session_duration_seconds = :sds, poll_interval_seconds = :pis,
                  enabled = :enabled, updated_at = now()
                WHERE name = :name
                """)
                .param("name", d.name()).param("mp", d.modelPurpose())
                .param("prompt", d.promptText()).param("schema", d.outputSchema().toString())
                .param("schedule", d.schedule()).param("turns", d.maxTurns())
                .param("secs", d.maxRunSeconds()).param("completion", d.completionPath())
                .param("esp", d.eventSourcePath()).param("sds", d.sessionDurationSeconds())
                .param("pis", d.pollIntervalSeconds()).param("enabled", d.enabled())
                .update();
        replaceTools(d);
    }

    private void replaceTools(AgentDefinition d) {
        jdbc.sql("DELETE FROM agent_tool_binding WHERE agent_name = :n")
                .param("n", d.name()).update();
        for (var t : d.tools()) {
            jdbc.sql("""
                    INSERT INTO agent_tool_binding
                      (agent_name, tool_name, description, default_params, ordinal)
                    VALUES (:agent, :tool, :desc, CAST(:params AS jsonb), :ord)
                    """)
                    .param("agent", d.name()).param("tool", t.toolName())
                    .param("desc", t.description())
                    .param("params", t.defaultParams() == null ? null : t.defaultParams().toString())
                    .param("ord", t.ordinal())
                    .update();
        }
    }

    public Optional<AgentDefinition> find(String name) {
        // Materialize base row first, then load tools in a second pass
        // to avoid running a nested query while the outer ResultSet is open.
        var rows = jdbc.sql("SELECT * FROM agent_definition WHERE name = :n")
                .param("n", name)
                .query(AgentDefinitionStore::mapBaseRow)
                .list();

        return rows.stream().findFirst().map(r -> new AgentDefinition(
                r.name(), r.modelPurpose(), r.promptText(), readJson(r.outputSchema()),
                r.schedule(), r.maxTurns(), r.maxRunSeconds(), r.completionPath(),
                r.eventSourcePath(), r.sessionDurationSeconds(), r.pollIntervalSeconds(),
                r.enabled(), loadTools(r.name())));
    }

    public List<AgentDefinition> findAllEnabled() {
        // Materialize base rows first, then load tools in a second pass.
        var rows = jdbc.sql("SELECT * FROM agent_definition WHERE enabled = TRUE ORDER BY name")
                .query(AgentDefinitionStore::mapBaseRow)
                .list();

        var result = new ArrayList<AgentDefinition>(rows.size());
        for (var r : rows) {
            result.add(new AgentDefinition(
                    r.name(), r.modelPurpose(), r.promptText(), readJson(r.outputSchema()),
                    r.schedule(), r.maxTurns(), r.maxRunSeconds(), r.completionPath(),
                    r.eventSourcePath(), r.sessionDurationSeconds(), r.pollIntervalSeconds(),
                    r.enabled(), loadTools(r.name())));
        }
        return result;
    }

    private List<ToolBinding> loadTools(String agentName) {
        return jdbc.sql("SELECT * FROM agent_tool_binding WHERE agent_name = :n ORDER BY ordinal")
                .param("n", agentName)
                .query((trs, i) -> new ToolBinding(
                        trs.getString("tool_name"),
                        trs.getString("description"),
                        readJsonNullable(trs.getString("default_params")),
                        trs.getInt("ordinal")))
                .list();
    }

    private JsonNode readJson(String s) {
        try { return mapper.readTree(s); }
        catch (Exception e) { throw new RuntimeException("bad output_schema json", e); }
    }

    private JsonNode readJsonNullable(String s) {
        return s == null ? null : readJson(s);
    }
}
