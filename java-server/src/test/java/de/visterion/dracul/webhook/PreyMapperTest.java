package de.visterion.dracul.webhook;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreyMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void mapsPreyWithDefaultsAndDiscoveredBy() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"ACME","companyName":"Acme","confidence":0.8,
                      "thesis":"t","signals":["s1"],"risks":["r1"]}]}
            """).path("prey");
        List<Prey> prey = new PreyMapper().map(node, "strigoi-echo", "PEAD", "3m", false);

        assertThat(prey).singleElement().satisfies(p -> {
            assertThat(p.symbol()).isEqualTo("ACME");
            assertThat(p.anomalyType()).isEqualTo("PEAD");
            assertThat(p.horizon()).isEqualTo("3m");
            assertThat(p.discoveredBy()).isEqualTo("strigoi-echo");
            assertThat(p.signals()).containsExactly("s1");
        });
    }

    @Test
    void skipsBlankSymbolWhenRequested() throws Exception {
        var node = json.readTree("""
            {"prey":[{"symbol":"","companyName":"x","confidence":0.5}]}
            """).path("prey");
        assertThat(new PreyMapper().map(node, "strigoi-merger", "MERGER_ARB", "3m", true)).isEmpty();
    }
}
