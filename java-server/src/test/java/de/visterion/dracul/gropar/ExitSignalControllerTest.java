package de.visterion.dracul.gropar;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExitSignalControllerTest {

    @AfterEach
    void clearUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void listsLatestSignalsForCurrentUser() {
        // Set a non-default current user so the test proves the controller scopes to
        // CurrentUserHolder.get() (not the old hardcoded "default").
        CurrentUserHolder.set("alice@x.com");
        var repo = mock(ExitSignalRepository.class);
        var sig = new ExitSignal("1", null, "ACME", "SELL", List.of("STOP_LOSS"),
                -8.0, "WEAKENING", "r", 0.7, "run", "2026-06-14T22:00:00Z");
        when(repo.findLatestByUser("alice@x.com", 100)).thenReturn(List.of(sig));

        var out = new ExitSignalController(repo).list();

        assertThat(out).singleElement().satisfies(s -> assertThat(s.symbol()).isEqualTo("ACME"));
        verify(repo).findLatestByUser("alice@x.com", 100);
        verify(repo, never()).findLatestByUser("default", 100);
    }
}
