package de.visterion.dracul.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void parsesSingleOrigin() {
        assertThat(CorsConfig.parseOrigins("https://dracul.example.com"))
                .containsExactly("https://dracul.example.com");
    }

    @Test
    void parsesCommaSeparatedOriginsAndTrims() {
        assertThat(CorsConfig.parseOrigins("http://localhost:5173, https://dracul.example.com"))
                .containsExactly("http://localhost:5173", "https://dracul.example.com");
    }

    @Test
    void dropsBlankEntries() {
        assertThat(CorsConfig.parseOrigins("https://dracul.example.com, ,"))
                .containsExactly("https://dracul.example.com");
    }
}
