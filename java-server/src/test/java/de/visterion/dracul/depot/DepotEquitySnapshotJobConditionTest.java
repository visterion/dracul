package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The kill switch. Deliberately an ApplicationContextRunner and NOT a @SpringBootTest: the
 * property is set in application.yaml, so a full-context test would pass with or without
 * matchIfMissing and would prove nothing.
 */
class DepotEquitySnapshotJobConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AgoraDepotClient.class, () -> mock(AgoraDepotClient.class))
            .withBean(DepotEquitySnapshotRepository.class,
                    () -> mock(DepotEquitySnapshotRepository.class))
            .withUserConfiguration(DepotEquitySnapshotJob.class);

    @Test
    void beanIsRegisteredWhenThePropertyIsAbsent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(DepotEquitySnapshotJob.class));
    }

    @Test
    void beanIsAbsentWhenTheKillSwitchIsOff() {
        runner.withPropertyValues("dracul.depots.equity-snapshot.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DepotEquitySnapshotJob.class));
    }
}
