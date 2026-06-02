package de.visterion.dracul;

import de.visterion.dracul.hunting.yahoo.YahooEarningsAdapter;
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
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.echo.webhook-token=tok-echo-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.strigoi.echo.schedule=0 30 14 * * *"
})
class StrigoiEchoRegistrarIT {

    @MockitoBean YahooEarningsAdapter yahoo;
    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("strigoi-echo").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("strigoi-echo");
        assertThat(detail.model_purpose()).isEqualTo("routine");
        assertThat(detail.completion_webhook()).isEqualTo("http://test.invalid:9090/api/strigoi-echo/complete");
        assertThat(detail.completion_webhook_token()).isEqualTo("tok-echo-it");
        assertThat(detail.schedule()).isEqualTo("0 30 14 * * *");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_recent_pead_candidates");
        assertThat(detail.tools().get(0).webhook_url()).isEqualTo(
                "http://test.invalid:9090/api/strigoi-echo/tools/fetch-candidates");
    }
}
