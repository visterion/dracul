package de.visterion.dracul.settings;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
    "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
    "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
    "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true"
})
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SettingsControllerRosterTest {

    @Autowired SettingsController controller;

    @Test
    void rosterIsDerivedFromProvidersAndContainsExactlyTheSixStrigoi() {
        assertThat(controller.strigoiNames()).containsExactly(
                "strigoi-echo", "strigoi-index", "strigoi-insider",
                "strigoi-lazarus", "strigoi-merger", "strigoi-spin");
    }
}
