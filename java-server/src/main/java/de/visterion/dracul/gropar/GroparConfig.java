package de.visterion.dracul.gropar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
class GroparConfig {

    @Bean
    RiskMetricsService groparRiskMetricsService(
            @Value("${dracul.gropar.initial-stop-atr-multiple:3.0}") String initialStopMultiple,
            @Value("${dracul.gropar.giveback-activation-r:1.5}") String givebackActivationR,
            @Value("${dracul.gropar.giveback-threshold-pct:35}") String givebackThresholdPct,
            @Value("${dracul.gropar.giveback-atr-multiple:2.0}") String givebackAtrMultiple) {
        return new RiskMetricsService(new RiskMetricsService.Params(
                new BigDecimal(initialStopMultiple),
                new BigDecimal(givebackActivationR),
                new BigDecimal(givebackThresholdPct).divide(BigDecimal.valueOf(100), java.math.MathContext.DECIMAL64),
                new BigDecimal(givebackAtrMultiple)));
    }
}
