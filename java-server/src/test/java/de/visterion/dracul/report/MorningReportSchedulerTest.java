package de.visterion.dracul.report;

import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MorningReportSchedulerTest {

    /** Builds a minimal held-position WatchlistItem (real record, not mock,
     *  because records are final — mocking inside a when() arg causes
     *  UnfinishedStubbingException in Mockito). */
    private WatchlistItem held(String id, String owner) {
        return new WatchlistItem(id, "AAA", "Aaa",
                100.0, 0.0,
                "ACTIVE", "2025-01-01T00:00:00Z", "HELD",
                null, List.of(), List.of(),
                100.0, 10.0,
                owner, "USD", "USD");
    }

    @Test
    void sendsDigestPerOwnerWithHeldPositions() {
        WatchlistRepository wl = mock(WatchlistRepository.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        WatchlistItem item = held("1", "u@x.com");
        when(wl.findAll()).thenReturn(List.of(item));
        when(svc.build("u@x.com")).thenReturn(
                new MorningReport("t", 1, 0, 0,
                        List.of(new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "SELL", null, null, null,
                                new OrderTicket("SELL", "AAA", 10, null, null, null), false))));

        new MorningReportScheduler(wl, svc, tg).run();

        verify(svc).build("u@x.com");
        verify(tg).notifyDigest(contains("AAA"));
    }

    @Test
    void rendersTargetCheckmarkWhenTargetReached() {
        // render() is package-private and pure — call it directly, no mocks needed.
        // Use a TRIM line: render() omits HOLD lines, so only SELL/TRIM are emitted.
        var scheduler = new MorningReportScheduler(
                mock(WatchlistRepository.class), mock(MorningReportService.class),
                mock(TelegramNotifier.class));
        var report = new MorningReport("t", 0, 1, 0, List.of(
                new MorningReportLine("AAA", "Aaa", 10, 100,
                        new java.math.BigDecimal("110"), new java.math.BigDecimal("105"),
                        new java.math.BigDecimal("90"), 4.5, "TRIM", null, null, null,
                        new OrderTicket("TRIM", "AAA", 3, null, null, null),
                        true))); // targetReached
        String text = scheduler.render("u@x.com", report);
        assertThat(text).contains("Ziel ✓").doesNotContain("Ziel 90");
    }

    @Test
    void skipsWhenNoHeldPositions() {
        WatchlistRepository wl = mock(WatchlistRepository.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(wl.findAll()).thenReturn(List.of());

        new MorningReportScheduler(wl, svc, tg).run();

        verifyNoInteractions(tg);
    }

    @Test
    void skipsPushWhenAllPositionsHold() {
        WatchlistRepository wl = mock(WatchlistRepository.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(wl.findAll()).thenReturn(List.of(held("1", "u@x.com")));
        when(svc.build("u@x.com")).thenReturn(
                new MorningReport("t", 0, 0, 1,
                        List.of(new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "HOLD", null, null, null,
                                new OrderTicket("HOLD", "AAA", 0, null, null, null), false))));

        new MorningReportScheduler(wl, svc, tg).run();

        verify(svc).build("u@x.com");
        verify(tg, never()).notifyDigest(anyString());
    }

    @Test
    void pushesOnlyActionableLines() {
        WatchlistRepository wl = mock(WatchlistRepository.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(wl.findAll()).thenReturn(List.of(held("1", "u@x.com")));
        when(svc.build("u@x.com")).thenReturn(
                new MorningReport("t", 1, 0, 1, List.of(
                        new MorningReportLine("BBB", "Bbb", 10, 100,
                                null, null, null, null, "SELL", null, null, null,
                                new OrderTicket("SELL", "BBB", 10, null, null, null), false),
                        new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "HOLD", null, null, null,
                                new OrderTicket("HOLD", "AAA", 0, null, null, null), false))));

        new MorningReportScheduler(wl, svc, tg).run();

        // pushes once, body lists the actionable SELL symbol but omits the HOLD symbol
        verify(tg).notifyDigest(argThat(s -> s.contains("BBB") && !s.contains("AAA")));
    }
}
