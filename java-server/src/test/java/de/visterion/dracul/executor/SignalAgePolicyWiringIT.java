package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dracul.executor.max-signal-age-days} is read exactly ONCE, in {@link ExecutorDefaults},
 * and reaches both consumers from there. Constructing the record by hand
 * ({@code new ExecutorDefaults().signalAgePolicy(5)}) would prove nothing about binding, so this
 * boots the real context with a NON-default value and asserts both ends see it.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.max-signal-age-days=7"
})
class SignalAgePolicyWiringIT {

    @Autowired SignalAgePolicy policy;
    @Autowired ExecutorWebhookController controller;

    @Test
    void theBeanAndTheControllersVetoConfigReadTheSameKey() {
        assertThat(policy.maxSignalAgeDays()).isEqualTo(7);
        assertThat(controller.vetoConfig().maxSignalAgeDays()).isEqualTo(7);
    }
}
