package de.visterion.dracul;

import de.visterion.dracul.hunting.edgar.EdgarFormFourAdapter;
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
        "dracul.strigoi.insider.enabled=true",
        "dracul.strigoi.insider.webhook-token=tok-reg-it",
        "dracul.public-url=http://test.invalid:9090",
        "dracul.strigoi.insider.schedule=0 30 14 * * *"
})
class StrigoiInsiderRegistrarIT {

    @MockitoBean EdgarFormFourAdapter edgar;
    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("strigoi-insider").orElseThrow(
                () -> new AssertionError("agent not registered"));
        assertThat(detail.name()).isEqualTo("strigoi-insider");
        assertThat(detail.model_purpose()).isEqualTo("routine");
        assertThat(detail.completion_webhook()).isEqualTo("http://test.invalid:9090/api/strigoi-insider/complete");
        assertThat(detail.completion_webhook_token()).isEqualTo("tok-reg-it");
        assertThat(detail.schedule()).isEqualTo("0 30 14 * * *");
        assertThat(detail.tools()).hasSize(1);
        assertThat(detail.tools().get(0).name()).isEqualTo("fetch_recent_clusters");
        assertThat(detail.tools().get(0).webhook_url()).isEqualTo(
                "http://test.invalid:9090/api/strigoi-insider/tools/fetch-clusters");
    }
}
