package de.visterion.dracul.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables Spring's @Scheduled support and pins the scheduler topology.
 *
 * <p><b>Two beans, deliberately.</b> Boot's default scheduler configuration is
 * {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})}, so a
 * SINGLE custom TaskScheduler bean would switch Boot's default off, and
 * {@code TaskSchedulerRouter.determineDefaultScheduler()} would resolve the one remaining bean
 * by type and hand it to EVERY @Scheduled method in the app. With two beans that by-type lookup
 * throws {@code NoUniqueBeanDefinitionException} and the router falls back to the bean NAMED
 * {@code taskScheduler} -- which is exactly the split we want.
 *
 * <p>{@code taskScheduler} is built from Boot's own {@link ThreadPoolTaskSchedulerBuilder} rather
 * than assembled by hand: that builder bean survives the conditional, so
 * {@code spring.task.scheduling.*}, any {@code ThreadPoolTaskSchedulerCustomizer} and any
 * {@code TaskDecorator} keep working. A hand-rolled ThreadPoolTaskScheduler would drop all of
 * that silently.
 *
 * <p>{@code equitySnapshotScheduler} exists so the equity-snapshot job cannot delay the jobs
 * already sharing the default pool of one -- {@code WatchlistPriceRefresher} runs every minute
 * inside the same window. Raising the shared pool instead would have de-serialised
 * {@code PositionReconciler} and {@code RenfieldScheduler}, which both fire at 12:00 UTC.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean
    ThreadPoolTaskScheduler equitySnapshotScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("equity-snapshot-");
        return scheduler;
    }
}
