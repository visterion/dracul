package de.visterion.dracul.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** Binds {@link ConvictionProfile} once (spec 2026-10-03 §5.2); a malformed value fails startup. */
@Configuration
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
class ConvictionProfileConfig {

    @Bean
    ConvictionProfile convictionProfile(
            @Value("${dracul.executor.profiles.conviction.emergency-stop-pct:0.35}") BigDecimal emergencyStopPct,
            @Value("${dracul.executor.profiles.conviction.target-pct:0.30}") BigDecimal targetPct,
            @Value("${dracul.executor.profiles.conviction.target-fraction:0.5}") BigDecimal targetFraction,
            @Value("${dracul.executor.profiles.conviction.trail-pct:0.30}") BigDecimal trailPct,
            @Value("${dracul.executor.profiles.conviction.min-entry-qty:2}") int minEntryQty,
            @Value("${dracul.executor.profiles.conviction.entry-broker-stop-pct:0.20}") BigDecimal entryBrokerStopPct,
            @Value("${dracul.strigoi.tech.position-pct:0.033}") BigDecimal positionPct) {
        return new ConvictionProfile(emergencyStopPct, targetPct, targetFraction, trailPct,
                minEntryQty, entryBrokerStopPct, positionPct);
    }
}
