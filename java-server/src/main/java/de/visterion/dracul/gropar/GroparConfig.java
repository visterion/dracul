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
}
