package de.visterion.dracul;

import de.visterion.dracul.hunting.wikipedia.WikipediaSp500Adapter;
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
        "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.index.webhook-token=tok-index-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.strigoi.index.schedule=0 0 7 * * 1-5"
})
class StrigoiIndexRegistrarIT {

    @MockitoBean WikipediaSp500Adapter wikipedia;
    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("strigoi-index").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("strigoi-index");
        assertThat(detail.model_purpose()).isEqualTo("routine");
        assertThat(detail.completion_webhook())
                .isEqualTo("http://test.invalid:9090/api/strigoi-index/complete");
        assertThat(detail.schedule()).isEqualTo("0 0 7 * * 1-5");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_recent_index_additions");
        assertThat(detail.tools().get(0).webhook_url())
                .isEqualTo("http://test.invalid:9090/api/strigoi-index/tools/fetch-candidates");
    }
}
