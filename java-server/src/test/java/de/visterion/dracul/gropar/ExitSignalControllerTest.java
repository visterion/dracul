package de.visterion.dracul.gropar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExitSignalControllerTest {

    @Test
    void listsLatestSignals() {
        var repo = mock(ExitSignalRepository.class);
        var sig = new ExitSignal("1", null, "ACME", "SELL", List.of("STOP_LOSS"),
                -8.0, "WEAKENING", "r", 0.7, "run", "2026-06-14T22:00:00Z");
        when(repo.findLatestByUser("default", 100)).thenReturn(List.of(sig));
        var out = new ExitSignalController(repo).list();
        assertThat(out).singleElement().satisfies(s -> assertThat(s.symbol()).isEqualTo("ACME"));
    }
}
