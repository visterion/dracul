package de.visterion.dracul.executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** Persists named, versioned snapshots of the executor's exit-rule parameters. */
@Repository
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class RuleVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RuleVersionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(RuleVersion v) {
        jdbc.sql("""
                INSERT INTO rule_versions (rule_version, valid_from, changes, prompt_hash, params)
                VALUES (:ruleVersion, :validFrom, :changes, :promptHash, CAST(:params AS jsonb))
                ON CONFLICT (rule_version) DO UPDATE SET
                    valid_from  = :validFrom,
                    changes     = :changes,
                    prompt_hash = :promptHash,
                    params      = CAST(:params AS jsonb)
                """)
                .param("ruleVersion", v.ruleVersion())
                .param("validFrom", LocalDate.parse(v.validFrom()))
                .param("changes", v.changes())
                .param("promptHash", v.promptHash())
                .param("params", writeJson(v.params()))
                .update();
    }

    public RuleVersion find(String ruleVersion) {
        return jdbc.sql("SELECT * FROM rule_versions WHERE rule_version = :ruleVersion")
                .param("ruleVersion", ruleVersion)
                .query(this::mapRow)
                .optional()
                .orElse(null);
    }

    public boolean exists(String ruleVersion) {
        return jdbc.sql("SELECT count(*) FROM rule_versions WHERE rule_version = :ruleVersion")
                .param("ruleVersion", ruleVersion)
                .query(Integer.class)
                .single() > 0;
    }

    private RuleVersion mapRow(ResultSet rs, int n) throws SQLException {
        Object validFromObj = rs.getObject("valid_from");
        return new RuleVersion(
                rs.getString("rule_version"),
                validFromObj == null ? null : validFromObj.toString(),
                rs.getString("changes"),
                rs.getString("prompt_hash"),
                readJson(rs.getString("params")));
    }

    private String writeJson(JsonNode node) {
        try { return mapper.writeValueAsString(node == null ? mapper.readTree("{}") : node); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize rule-version params", e); }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return null;
        }
    }
}
