package de.visterion.dracul.prey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private List<String> readList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
