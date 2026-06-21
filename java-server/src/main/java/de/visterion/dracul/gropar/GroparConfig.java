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
    ExitIndicatorService groparIndicatorService(
            @Value("${dracul.gropar.atr-period:22}") int atrPeriod,
            @Value("${dracul.gropar.atr-multiple:3.0}") String atrMultiple,
            @Value("${dracul.gropar.ma-fast:50}") int maFast,
            @Value("${dracul.gropar.ma-slow:200}") int maSlow) {
        return new ExitIndicatorService(new ExitIndicatorService.Params(
                atrPeriod, new BigDecimal(atrMultiple), maFast, maSlow, 250));
    }

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
