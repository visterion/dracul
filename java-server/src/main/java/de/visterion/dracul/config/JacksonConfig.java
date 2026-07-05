package de.visterion.dracul.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        // Jackson 3's JsonMapper auto-discovers and registers modules via SPI by default
        // (findAndRegisterModules() was removed; no explicit call needed). Declared as the
        // concrete JsonMapper subtype (rather than the abstract ObjectMapper) so it is
        // compatible with Spring's JacksonJsonHttpMessageConverter, which requires JsonMapper.
        return new JsonMapper();
    }
}
