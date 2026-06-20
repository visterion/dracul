package de.visterion.dracul;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.settings.CurrencySetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SettingsCurrencyControllerIT {

    @LocalServerPort int port;
    @Autowired AppSettingsRepository repo;
    @Autowired ObjectMapper objectMapper;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
        repo.setDisplayCurrency("EUR");
    }

    @AfterEach
    void restore() { repo.setDisplayCurrency("EUR"); }

    @Test
    void getReturnsDefaultEur() {
        var body = rest.get().uri("/api/settings/currency").retrieve().body(CurrencySetting.class);
        assertThat(body.currency()).isEqualTo("EUR");
    }

    @Test
    void putPersistsAndValidates() {
        var ok = rest.put().uri("/api/settings/currency")
                .contentType(MediaType.APPLICATION_JSON).body(new CurrencySetting("USD"))
                .retrieve().body(CurrencySetting.class);
        assertThat(ok.currency()).isEqualTo("USD");
        assertThat(repo.getDisplayCurrency()).isEqualTo("USD");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            rest.put().uri("/api/settings/currency")
                .contentType(MediaType.APPLICATION_JSON).body(new CurrencySetting("XYZ"))
                .retrieve().toBodilessEntity()
        ).isInstanceOf(org.springframework.web.client.HttpClientErrorException.BadRequest.class);
    }
}
