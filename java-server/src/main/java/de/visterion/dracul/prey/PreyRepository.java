package de.visterion.dracul.prey;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PreyRepository {

    private static final Logger log = LoggerFactory.getLogger(PreyRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PreyRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Prey> findAllByUser(String userId) {
        return jdbc.sql("""
                SELECT id, symbol, company_name, anomaly_type, confidence, thesis,
                       signals, risks, horizon, discovered_by, discovered_at
                FROM prey
                WHERE user_id = :userId
                ORDER BY discovered_at DESC
                """)
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    public List<Prey> findByDiscoveredBy(String discoveredBy, String userId) {
        return jdbc.sql("""
                SELECT id, symbol, company_name, anomaly_type, confidence, thesis,
                       signals, risks, horizon, discovered_by, discovered_at
                FROM prey
                WHERE discovered_by = :discoveredBy AND user_id = :userId
                ORDER BY discovered_at DESC
                """)
                .param("discoveredBy", discoveredBy)
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    private Prey mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Prey(
                rs.getString("id"),
                rs.getString("symbol"),
                rs.getString("company_name"),
                rs.getString("anomaly_type"),
                rs.getDouble("confidence"),
                rs.getString("thesis"),
                readList(rs.getString("signals")),
                readList(rs.getString("risks")),
                rs.getString("horizon"),
                rs.getString("discovered_by"),
                rs.getString("discovered_at")
        );
    }

    public void insertAll(java.util.List<Prey> prey) {
        if (prey.isEmpty()) return;
        for (Prey p : prey) {
            String signalsJson;
            String risksJson;
            try {
                signalsJson = mapper.writeValueAsString(p.signals());
                risksJson = mapper.writeValueAsString(p.risks());
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize prey signals/risks", e);
            }
            jdbc.sql("""
                    INSERT INTO prey
                      (id, symbol, company_name, anomaly_type, confidence,
                       thesis, signals, risks, horizon, discovered_by, discovered_at, user_id)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::timestamptz, ?)
                    """)
                    .params(p.id(), p.symbol(), p.companyName(), p.anomalyType(),
                            p.confidence(), p.thesis(),
                            signalsJson, risksJson,
                            p.horizon(), p.discoveredBy(), p.discoveredAt(), "default")
                    .update();
        }
    }

    private List<String> readList(String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
