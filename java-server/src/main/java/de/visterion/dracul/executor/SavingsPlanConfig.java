package de.visterion.dracul.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Locale;

/** Binds {@link SavingsPlanSettings} once (a malformed value fails startup) and the one
 *  transaction template the feature uses: step-7 bookings and the reconcile matrix rows. Broker
 *  calls are never made inside it (spec 2026-10-06 §3.4). */
@Configuration
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
class SavingsPlanConfig {

    @Bean
    SavingsPlanSettings savingsPlanSettings(
            @Value("${dracul.executor.savings-plan.enabled:false}") boolean enabled,
            @Value("${dracul.executor.savings-plan.monthly-pct:0.02}") BigDecimal monthlyPct,
            @Value("${dracul.executor.savings-plan.max-position-pct:0.08}") BigDecimal maxPositionPct,
            @Value("${dracul.executor.savings-plan.basket-cap-pct:0.50}") BigDecimal basketCapPct,
            @Value("${dracul.executor.savings-plan.limit-premium-pct:0.02}") BigDecimal limitPremiumPct,
            @Value("${dracul.executor.savings-plan.catch-up-weekdays:3}") int catchUpWeekdays,
            @Value("${dracul.executor.savings-plan.window-start-utc:21:15}") String windowStartUtc,
            @Value("${dracul.executor.savings-plan.tif:gtc}") String tif,
            @Value("${dracul.executor.savings-plan.place-first:false}") boolean placeFirst) {
        return new SavingsPlanSettings(enabled, monthlyPct, maxPositionPct, basketCapPct,
                limitPremiumPct, catchUpWeekdays, LocalTime.parse(windowStartUtc.trim()),
                tif == null ? null : tif.trim().toLowerCase(Locale.ROOT), placeFirst);
    }

    @Bean
    TransactionOperations savingsPlanTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
