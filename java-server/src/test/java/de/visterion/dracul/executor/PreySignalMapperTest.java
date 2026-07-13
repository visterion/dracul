package de.visterion.dracul.executor;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreySignalMapperTest {

    private final AgentVersionResolver versions = mock(AgentVersionResolver.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PreySignalMapper mapper = new PreySignalMapper(versions, objectMapper);

    private Prey samplePrey() {
        return new Prey(
                "prey-1", "ACME", "Acme Corp", "SPINOFF",
                0.73, "Forced index selling post-separation.",
                List.of("Dropped from parent's index"), List.of("Thin float"),
                List.of("Close below 90.00"),
                "6m", "strigoi-spin", "2026-07-08T10:00:00Z");
    }

    private Prey preyWith(String symbol, String anomalyType, String thesis, List<String> signals,
            List<String> risks, String horizon, List<String> killCriteria, double confidence) {
        return new Prey(
                "prey-" + symbol, symbol, symbol + " Corp", anomalyType,
                confidence, thesis, signals, risks, killCriteria,
                horizon, "strigoi-spin", "2026-07-08T10:00:00Z");
    }

    @Test
    void mapsPreyFieldsOntoSignal() {
        when(versions.versionFor("strigoi-spin")).thenReturn("p-abc");

        ExecutorSignal s = mapper.map(samplePrey());

        assertThat(s.source()).isEqualTo("strigoi-spin");
        assertThat(s.agentVersion()).isEqualTo("p-abc");
        assertThat(s.symbol()).isEqualTo("ACME");
        assertThat(s.confidence()).isEqualTo(0.73);
        assertThat(s.mechanism()).isEqualTo("SPINOFF");
        assertThat(s.horizon()).isEqualTo("6m");
        assertThat(s.referencePrice()).isNull();
        assertThat(s.createdAt()).isNull();
    }

    @Test
    void directionIsAlwaysBuy() {
        assertThat(mapper.map(samplePrey()).direction()).isEqualTo("BUY");
    }

    @Test
    void statusIsPending() {
        assertThat(mapper.map(samplePrey()).status()).isEqualTo("PENDING");
    }

    @Test
    void killCriteriaPassesThrough() {
        assertThat(mapper.map(samplePrey()).killCriteria())
                .containsExactly("Close below 90.00");
    }

    @Test
    void signalIdIsGeneratedAndNonNull() {
        ExecutorSignal a = mapper.map(samplePrey());
        ExecutorSignal b = mapper.map(samplePrey());
        assertThat(a.signalId()).isNotBlank();
        assertThat(b.signalId()).isNotBlank();
        assertThat(a.signalId()).isNotEqualTo(b.signalId()); // freshly generated each call
    }

    @Test
    void map_buildsThesisSnapshotFromPrey() {
        Prey p = preyWith("HELE", "PEAD", "big beat", List.of("sig1"), List.of("risk1"),
                "1M", List.of("drift reverses"), 0.7);
        ExecutorSignal s = mapper.map(p);
        JsonNode thesis = s.thesis();
        assertThat(thesis).isNotNull();
        assertThat(thesis.get("summary").asString()).isEqualTo("big beat");
        assertThat(thesis.get("anomalyTypes").get(0).asString()).isEqualTo("PEAD");
        assertThat(thesis.get("horizon").asString()).isEqualTo("1M");
    }

    @Test
    void map_nullThesis_yieldsNullThesisColumn() {
        Prey p = preyWith("HELE", "PEAD", null, List.of(), List.of(), "1M", List.of(), 0.7);
        assertThat(mapper.map(p).thesis()).isNull();   // no {summary:null,...} husk
    }
}
