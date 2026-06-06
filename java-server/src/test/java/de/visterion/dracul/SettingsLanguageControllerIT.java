package de.visterion.dracul;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.settings.AppSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SettingsLanguageControllerIT {

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
        repo.setLanguage("de");
    }

    @AfterEach
    void restore() { repo.setLanguage("de"); }

    @Test
    void getLanguageReturnsCurrentSetting() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rest.get()
                .uri("/api/settings/language")
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        assertThat(body.get("language")).isEqualTo("de");
    }

    @Test
    void putLanguageUpdatesSettingAndReturnsNewValue() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rest.put()
                .uri("/api/settings/language")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("language", "en"))
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        assertThat(body.get("language")).isEqualTo("en");
        assertThat(repo.getLanguage()).isEqualTo("en");
    }

    @Test
    void putLanguageWithUnsupportedLocaleReturns400AndLeavesRepoUnchanged() {
        try {
            rest.put()
                    .uri("/api/settings/language")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("language", "fr"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
            assertThat(repo.getLanguage()).isEqualTo("de");
            return;
        }
        throw new AssertionError("Expected 400 BAD_REQUEST");
    }
}
