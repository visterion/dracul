package de.visterion.dracul.report;

import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                                new OrderTicket("SELL", "AAA", 10, null, null, null)))));

        new MorningReportScheduler(wl, svc, tg).run();

        verify(svc).build("u@x.com");
        verify(tg).notifyDigest(contains("AAA"));
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
}
