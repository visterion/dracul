package de.visterion.dracul;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.voievod.VoievodRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.voievod.enabled=true")
class VoievodRegistrarLanguageIT {

    @Autowired VoievodRegistrar registrar;
    @Autowired AppSettingsRepository settings;

    @AfterEach
    void restore() { settings.setLanguage("de"); }

    @Test
    void systemPromptCarriesGermanDirectiveByDefault() {
        settings.setLanguage("de");
        var req = registrar.buildRequestForTest();
        assertThat(req.system_prompt()).contains("German");
        assertThat(req.system_prompt()).contains("do not translate");
    }

    @Test
    void systemPromptSwitchesToEnglishWhenConfigured() {
        settings.setLanguage("en");
        var req = registrar.buildRequestForTest();
        assertThat(req.system_prompt()).contains("English");
    }
}
