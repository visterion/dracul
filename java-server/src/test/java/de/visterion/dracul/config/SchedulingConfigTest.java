package de.visterion.dracul.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot job gets its own single-threaded scheduler so it cannot delay the jobs that
 * already share Boot's default pool of one. Declaring only ONE TaskScheduler bean would achieve
 * the opposite: Boot's default is @ConditionalOnMissingBean(TaskScheduler.class), so a single
 * custom bean becomes the default for every @Scheduled method in the app. These tests pin the
 * two-bean arrangement that makes the router fall back to the name "taskScheduler".
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void bothSchedulersExistAndAreDistinctInstances() {
        runner.run(ctx -> {
            TaskScheduler dflt = ctx.getBean("taskScheduler", TaskScheduler.class);
            TaskScheduler equity = ctx.getBean("equitySnapshotScheduler", TaskScheduler.class);
            assertThat(dflt).isNotSameAs(equity);
        });
    }

    @Test
    void defaultSchedulerKeepsBootsPoolSizeAndThreadPrefix() {
        runner.run(ctx -> {
            ThreadPoolTaskScheduler dflt = ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            // NOT getPoolSize(): once the scheduler is initialised that delegates to the
            // executor's LIVE thread count, which is 0 until a task actually fires. The
            // configured size lives on the executor's core pool.
            assertThat(dflt.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(dflt.getThreadNamePrefix()).isEqualTo("scheduling-");
        });
    }

    @Test
    void defaultSchedulerStillHonoursSpringTaskSchedulingPoolSize() {
        runner.withPropertyValues("spring.task.scheduling.pool.size=3").run(ctx -> {
            ThreadPoolTaskScheduler dflt = ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(dflt.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
        });
    }

    @Test
    void equitySchedulerIsSingleThreadedAndOwnPrefix() {
        runner.run(ctx -> {
            ThreadPoolTaskScheduler equity =
                    ctx.getBean("equitySnapshotScheduler", ThreadPoolTaskScheduler.class);
            assertThat(equity.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(equity.getThreadNamePrefix()).isEqualTo("equity-snapshot-");
        });
    }
}
