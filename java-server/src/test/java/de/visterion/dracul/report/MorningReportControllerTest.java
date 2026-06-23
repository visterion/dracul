package de.visterion.dracul.report;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MorningReportControllerTest {

    @AfterEach
    void clear() { CurrentUserHolder.clear(); }

    @Test
    void servesReportForCurrentUser() {
        CurrentUserHolder.set("alice@x.com");
        var svc = mock(MorningReportService.class);
        var report = new MorningReport("2026-06-23T07:00:00Z", 0, 0, 0, List.of());
        when(svc.build("alice@x.com")).thenReturn(report);

        var out = new MorningReportController(svc).get();

        assertThat(out.generatedAt()).isEqualTo("2026-06-23T07:00:00Z");
        verify(svc).build("alice@x.com");
        verify(svc, never()).build("default");
    }
}
