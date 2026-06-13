package de.visterion.dracul.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void derivesOriginFromPublicUrl() {
        assertThat(CorsConfig.originOf("https://dracul.ufelmann.com")).isEqualTo("https://dracul.ufelmann.com");
        assertThat(CorsConfig.originOf("https://dracul.ufelmann.com/")).isEqualTo("https://dracul.ufelmann.com");
        assertThat(CorsConfig.originOf("https://dracul.ufelmann.com/api/x")).isEqualTo("https://dracul.ufelmann.com");
        assertThat(CorsConfig.originOf("http://localhost:8080")).isEqualTo("http://localhost:8080");
    }

    @Test
    void fallsBackOnUnparseableInput() {
        assertThat(CorsConfig.originOf("not a url")).isEqualTo("not a url");
    }
}
