package de.visterion.dracul;

import de.visterion.dracul.hunting.finnhub.FinnhubFundamentalsAdapter;
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
        "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.lazarus.webhook-token=tok-lazarus-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.strigoi.lazarus.schedule=0 0 6 * * 1-5"
})
class StrigoiLazarusRegistrarIT {

    @MockitoBean FinnhubFundamentalsAdapter fundamentals;
    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("strigoi-lazarus").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("strigoi-lazarus");
        assertThat(detail.model_purpose()).isEqualTo("reasoning");
        assertThat(detail.completion_webhook())
                .isEqualTo("http://test.invalid:9090/api/strigoi-lazarus/complete");
        assertThat(detail.schedule()).isEqualTo("0 0 6 * * 1-5");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_quality_at_low_candidates");
        assertThat(detail.tools().get(0).webhook_url())
                .isEqualTo("http://test.invalid:9090/api/strigoi-lazarus/tools/fetch-candidates");
    }
}
