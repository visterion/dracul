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
        "dracul.daywalker.enabled=true",
        "dracul.daywalker.webhook-token=tok-dw-it",
        "dracul.daywalker.session-cron=0 30 13 * * 1-5",
        "dracul.public-url=http://test.invalid:9090"
})
class DaywalkerRegistrarIT {

    @Autowired VistierieClient vistierie;

    @Test
    void agentIsRegisteredAfterStartup() {
        var detail = vistierie.getAgent("daywalker").orElseThrow(
                () -> new AssertionError("daywalker agent not registered"));
        assertThat(detail.name()).isEqualTo("daywalker");
        assertThat(detail.model_purpose()).isEqualTo("reasoning");
        assertThat(detail.completion_webhook())
                .isEqualTo("http://test.invalid:9090/api/daywalker/complete");
        assertThat(detail.schedule()).isEqualTo("0 30 13 * * 1-5");
    }
}
