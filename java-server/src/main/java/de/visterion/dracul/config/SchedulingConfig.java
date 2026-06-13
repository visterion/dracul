package de.visterion.dracul.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Spring's @Scheduled support (used by WatchlistPriceRefresher). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
