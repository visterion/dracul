package de.visterion.dracul;

import de.visterion.dracul.hunting.edgar.EdgarMergerAdapter;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.merger.enabled=true",
        "dracul.strigoi.merger.webhook-token=tok-merger-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.strigoi.merger.schedule=0 0 5 * * 1-5"
})
class StrigoiMergerRegistrarIT {

    @MockitoBean EdgarMergerAdapter edgarMerger;
    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("strigoi-merger").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("strigoi-merger");
        assertThat(detail.model_purpose()).isEqualTo("reasoning");
        assertThat(detail.completion_webhook())
                .isEqualTo("http://test.invalid:9090/api/strigoi-merger/complete");
        assertThat(detail.schedule()).isEqualTo("0 0 5 * * 1-5");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_recent_merger_candidates");
        assertThat(detail.tools().get(0).webhook_url())
                .isEqualTo("http://test.invalid:9090/api/strigoi-merger/tools/fetch-candidates");
    }
}
