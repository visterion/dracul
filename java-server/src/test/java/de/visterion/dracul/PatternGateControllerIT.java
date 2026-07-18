package de.visterion.dracul;

import de.visterion.dracul.pattern.PatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternGateControllerIT {

    private static final String GATE_BODY =
            "{\"action\":\"update_gate\",\"gate\":{\"conditions\":[{\"field\":\"mechanism\","
            + "\"op\":\"eq\",\"value\":\"PEAD\"}]}}";

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired PatternRepository repo;
    @Autowired JdbcClient jdbc;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
    }

    private String freshPattern(String status) {
        String statement = "Gate API lesson " + UUID.randomUUID();
        repo.insertProposal("default", "strigoi-test", statement, 2);
        var p = repo.findAllByUser("default").stream()
                .filter(x -> statement.equals(x.statement()))
                .findFirst().orElseThrow();
        if (!"PENDING".equals(status)) repo.updateStatus(p.id(), "default", status);
        return p.id();
    }

    private JsonNode getPattern(String id) {
        var patterns = rest.get().uri("/api/patterns").retrieve().toEntity(JsonNode.class);
        return StreamSupport.stream(patterns.getBody().spliterator(), false)
                .filter(p -> id.equals(p.path("id").asText()))
                .findFirst().orElseThrow();
    }

    private int patch(String id, String body) {
        var response = rest.patch()
                .uri("/api/patterns/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.readTree(body))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {})
                .toBodilessEntity();
        return response.getStatusCode().value();
    }

    @Test
    void updateGateValidStoresGateAndGetExposesIt() {
        String id = freshPattern("PENDING");
        assertThat(patch(id, GATE_BODY)).isEqualTo(204);

        JsonNode p = getPattern(id);
        assertThat(p.path("gate").path("conditions").get(0).path("field").asText())
                .isEqualTo("mechanism");
        assertThat(p.path("blockedCount").asLong()).isZero();
    }

    @Test
    void updateGateExplicitNullClears() {
        String id = freshPattern("PENDING");
        patch(id, GATE_BODY);
        assertThat(patch(id, "{\"action\":\"update_gate\",\"gate\":null}")).isEqualTo(204);
        assertThat(getPattern(id).path("gate").isNull()).isTrue();
    }

    @Test
    void updateGateMissingGateFieldIs400() {
        String id = freshPattern("PENDING");
        assertThat(patch(id, "{\"action\":\"update_gate\"}")).isEqualTo(400);
    }

    @Test
    void updateGateInvalidGateIs400WithReasons() {
        String id = freshPattern("PENDING");
        var response = rest.patch()
                .uri("/api/patterns/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.readTree(
                        "{\"action\":\"update_gate\",\"gate\":{\"conditions\":[{\"field\":\"foo\","
                        + "\"op\":\"eq\",\"value\":\"x\"}]}}"))
                .retrieve()
                .onStatus(s -> s.value() == 400, (req, res) -> {})
                .toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("errors").isArray()).isTrue();
        assertThat(response.getBody().path("errors").get(0).asText()).contains("unknown field");
    }

    @Test
    void updateGateOnActivePatternAllowed() {
        String id = freshPattern("ACTIVE");
        assertThat(patch(id, GATE_BODY)).isEqualTo(204);
    }

    @Test
    void updateGateOnRejectedPatternIs400() {
        String id = freshPattern("REJECTED");
        assertThat(patch(id, GATE_BODY)).isEqualTo(400);
    }

    @Test
    void updateGateOnUnknownPatternIs404() {
        assertThat(patch(UUID.randomUUID().toString(), GATE_BODY)).isEqualTo(404);
    }

    @Test
    void updateGateDeletesAutoEvidence() {
        String id = freshPattern("ACTIVE");
        patch(id, GATE_BODY);
        repo.insertAutoEvidence(id, "AAA", "AAA", "PEAD", "2026-07-01T00:00:00Z", true,
                new java.math.BigDecimal("-5.00"), "gate-api-" + UUID.randomUUID());

        assertThat(patch(id, GATE_BODY.replace("PEAD", "SPINOFF"))).isEqualTo(204);

        Integer autoRows = jdbc.sql("SELECT COUNT(*) FROM pattern_evidence "
                        + "WHERE pattern_id = :id::uuid AND outcome_ref IS NOT NULL")
                .param("id", id).query(Integer.class).single();
        assertThat(autoRows).isZero();
    }

    @Test
    void approveOnlyFromPending() {
        String pending = freshPattern("PENDING");
        assertThat(patch(pending, "{\"action\":\"approve\"}")).isEqualTo(204);
        assertThat(getPattern(pending).path("status").asText()).isEqualTo("ACTIVE");

        String rejected = freshPattern("REJECTED");
        assertThat(patch(rejected, "{\"action\":\"approve\"}")).isEqualTo(400);
        assertThat(getPattern(rejected).path("status").asText()).isEqualTo("REJECTED");

        // Approving an already-ACTIVE pattern is also a 400 now.
        assertThat(patch(pending, "{\"action\":\"approve\"}")).isEqualTo(400);
    }

    @Test
    void approveArmsExistingGateWithoutTouchingIt() {
        String id = freshPattern("PENDING");
        patch(id, GATE_BODY);
        assertThat(patch(id, "{\"action\":\"approve\"}")).isEqualTo(204);
        JsonNode p = getPattern(id);
        assertThat(p.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(p.path("gate").isObject()).isTrue(); // status flip only, gate untouched
    }

    @Test
    void blockedCountReflectsDistinctGateBlockedSignals() {
        String id = freshPattern("ACTIVE");
        patch(id, GATE_BODY);
        insertGateReject("sig-bc-1-" + id, "PATTERN_GATE", id);
        insertGateReject("sig-bc-1-" + id, "PATTERN_GATE", id); // same signal again -> 1
        insertGateReject("sig-bc-2-" + id, "PATTERN_GATE", id);
        insertGateReject("sig-bc-3-" + id, "COOLDOWN", id);     // trace-only -> not counted

        assertThat(getPattern(id).path("blockedCount").asLong()).isEqualTo(2);
    }

    private void insertGateReject(String signalId, String reasonCode, String patternId) {
        String vetoResults = "[{\"check\":\"PATTERN_GATE\",\"passed\":false,"
                + "\"measured\":\"pattern_gate:" + patternId + " (x)\"}]";
        jdbc.sql("""
                INSERT INTO decision_log (log_id, rule_version, trigger_type, signal_id, symbol,
                                          veto_results, action, reason_code)
                VALUES (gen_random_uuid(), 'exec-test', 'SIGNAL', :signalId, 'ACME',
                        CAST(:vr AS jsonb), 'REJECT', :reasonCode)
                """)
                .param("signalId", signalId).param("vr", vetoResults)
                .param("reasonCode", reasonCode).update();
    }
}
