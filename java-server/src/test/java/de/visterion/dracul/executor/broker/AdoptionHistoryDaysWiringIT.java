package de.visterion.dracul.executor.broker;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dracul.executor.adoption-history-days} really binds through the real context. Constructing
 * the gateway by hand proves nothing about binding, so this boots with a NON-default value; the
 * default itself is pinned by {@code AgoraExecutionGatewayTest.ordersByRef_defaultWindowIsFourteenDays}
 * against {@link AgoraExecutionGateway#DEFAULT_ADOPTION_HISTORY_DAYS}, which the yaml default
 * mirrors.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.adoption-history-days=21"
})
class AdoptionHistoryDaysWiringIT {

    @Autowired AgoraExecutionGateway gateway;

    @Test
    void theGatewayReadsTheConfiguredWindow() {
        assertThat(gateway.adoptionHistoryDays()).isEqualTo(21);
    }
}
