package de.visterion.dracul;

import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.voievod.enabled=true",
        "dracul.voievod.webhook-token=tok-voievod-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.voievod.schedule=0 0 8 * * 1-5"
})
class VoievodRegistrarIT {

    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("voievod").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("voievod");
        assertThat(detail.model_purpose()).isEqualTo("reasoning");
        assertThat(detail.completion_webhook())
                .isEqualTo("http://test.invalid:9090/api/voievod/complete");
        assertThat(detail.schedule()).isEqualTo("0 0 8 * * 1-5");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_consensus_clusters");
        assertThat(detail.tools().get(0).webhook_url())
                .isEqualTo("http://test.invalid:9090/api/voievod/tools/fetch-candidates");
    }
}
