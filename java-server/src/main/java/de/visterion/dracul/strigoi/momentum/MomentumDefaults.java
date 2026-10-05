package de.visterion.dracul.strigoi.momentum;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
class MomentumDefaults {

    static final String NAME = MomentumSettings.AGENT;
    static final String FETCH = "fetch_momentum_ranking";

    /** Bound once; a malformed value fails startup. position-pct and min-entry-qty are the same
     *  keys the executor's ConvictionProfileConfig reads for exit profile MOMENTUM. */
    @Bean
    MomentumSettings momentumSettings(
            @Value("${dracul.strigoi.momentum.top-n:10}") int topN,
            @Value("${dracul.strigoi.momentum.refill-n:10}") int refillN,
            @Value("${dracul.strigoi.momentum.catch-up-weekdays:3}") int catchUpWeekdays,
            @Value("${dracul.strigoi.momentum.universe-min:480}") int universeMin,
            @Value("${dracul.strigoi.momentum.completeness-floor:0.95}") BigDecimal completenessFloor,
            @Value("${dracul.strigoi.momentum.position-pct:0.025}") BigDecimal positionPct,
            @Value("${dracul.strigoi.momentum.lookback-days:252}") int lookbackDays,
            @Value("${dracul.strigoi.momentum.skip-days:21}") int skipDays,
            @Value("${dracul.strigoi.momentum.gap-suspect-pct:0.35}") BigDecimal gapSuspectPct,
            @Value("${dracul.strigoi.momentum.min-price:5}") BigDecimal minPrice,
            @Value("${dracul.strigoi.momentum.min-entry-qty:1}") int minEntryQty,
            @Value("${dracul.strigoi.momentum.exclude-symbols:GOOG,FOX,NWS}") String excludeSymbols,
            @Value("${dracul.executor.connection:depot-1}") String executorConnection) {
        return new MomentumSettings(topN, refillN, catchUpWeekdays, universeMin, completenessFloor,
                positionPct, lookbackDays, skipDays, gapSuspectPct, minPrice, minEntryQty,
                MomentumSettings.parseSymbols(excludeSymbols), executorConnection);
    }
}
