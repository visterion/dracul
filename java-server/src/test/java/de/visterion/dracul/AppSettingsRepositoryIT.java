package de.visterion.dracul;

import de.visterion.dracul.settings.AppSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AppSettingsRepositoryIT {

    @Autowired AppSettingsRepository repo;
    @Autowired JdbcClient jdbc;

    @Test
    void defaultLanguageIsGermanFromSeed() {
        assertThat(repo.getLanguage()).isEqualTo("de");
    }

    @Test
    void setLanguagePersistsAndIsReadBack() {
        repo.setLanguage("en");
        assertThat(repo.getLanguage()).isEqualTo("en");
        repo.setLanguage("de");
    }

    @Test
    void getLanguageFallsBackToDeWhenRowMissing() {
        jdbc.sql("DELETE FROM app_settings WHERE key = 'language'").update();
        assertThat(repo.getLanguage()).isEqualTo("de");
        jdbc.sql("INSERT INTO app_settings(key, value) VALUES ('language','de')").update();
    }
}
