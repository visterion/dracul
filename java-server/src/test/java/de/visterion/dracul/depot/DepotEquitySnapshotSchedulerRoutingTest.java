package de.visterion.dracul.depot;

import de.visterion.dracul.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.SchedulingConfiguration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.SchedulingAwareRunnable;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 2 gave the snapshot job its own scheduler thread; these two tests are what actually pin
 * that arrangement. SchedulingConfigTest only asserts that both beans exist with the right pool
 * size and prefix -- it would stay green if someone marked equitySnapshotScheduler @Primary, at
 * which point Spring's ambiguity resolution hands it to EVERY @Scheduled method in the app and
 * the split is silently defeated.
 */
class DepotEquitySnapshotSchedulerRoutingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withBean(AgoraDepotClient.class, () -> mock(AgoraDepotClient.class))
            .withBean(DepotEquitySnapshotRepository.class,
                    () -> mock(DepotEquitySnapshotRepository.class))
            .withUserConfiguration(SchedulingConfig.class, DepotEquitySnapshotJob.class);

    @Test
    void onlyTheSnapshotJobsTasksCarryTheEquityQualifier() {
        runner.run(ctx -> {
            var bpp = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
            Set<ScheduledTask> tasks = bpp.getScheduledTasks();

            var qualifiers = tasks.stream()
                    .map(t -> t.getTask().getRunnable())
                    .filter(SchedulingAwareRunnable.class::isInstance)
                    .map(r -> ((SchedulingAwareRunnable) r).getQualifier())
                    .toList();

            // Both crons of the snapshot job, and nothing else in this context.
            assertThat(qualifiers).containsOnly("equitySnapshotScheduler");
            assertThat(tasks).hasSize(2);
        });
    }

    @Test
    void theDefaultSchedulerIsTheTaskSchedulerBeanNotTheEquityOne() {
        runner.run(ctx -> {
            var bpp = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
            Object router = org.springframework.test.util.ReflectionTestUtils
                    .getField(bpp, "localScheduler");
            assertThat(router).isNotNull();
            Object dflt = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(router, "determineDefaultScheduler");

            assertThat(dflt).isSameAs(ctx.getBean("taskScheduler"));
            assertThat(dflt).isNotSameAs(ctx.getBean("equitySnapshotScheduler"));
        });
    }
}
