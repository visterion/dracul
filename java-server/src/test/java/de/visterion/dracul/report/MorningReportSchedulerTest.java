package de.visterion.dracul.report;

import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MorningReportSchedulerTest {

    private static final String CONNECTION = "depot-1";
    private static final String OWNER = "u@x.com";

    private HeldPosition held(String symbol) {
        return new HeldPosition(symbol, BigDecimal.TEN, BigDecimal.valueOf(100),
                BigDecimal.valueOf(1000), BigDecimal.ZERO, null, null, null, null, null, null, null, null, null);
    }

    private MorningReportScheduler scheduler(HeldPositionService hp, MorningReportService svc, TelegramNotifier tg) {
        return new MorningReportScheduler(hp, svc, tg, OWNER, CONNECTION);
    }

    @Test
    void sendsDigestPerOwnerWithHeldPositions() {
        HeldPositionService hp = mock(HeldPositionService.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of(held("AAA")));
        when(svc.build(OWNER)).thenReturn(
                new MorningReport("t", 1, 0, 0,
                        List.of(new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "SELL", null, null, null,
                                new OrderTicket("SELL", "AAA", 10, null, null, null), false))));

        scheduler(hp, svc, tg).run();

        verify(svc).build(OWNER);
        verify(tg).notifyDigest(contains("AAA"));
    }

    @Test
    void rendersTargetCheckmarkWhenTargetReached() {
        // render() is package-private and pure — call it directly, no mocks needed.
        // Use a TRIM line: render() omits HOLD lines, so only SELL/TRIM are emitted.
        var scheduler = scheduler(
                mock(HeldPositionService.class), mock(MorningReportService.class),
                mock(TelegramNotifier.class));
        var report = new MorningReport("t", 0, 1, 0, List.of(
                new MorningReportLine("AAA", "Aaa", 10, 100,
                        new java.math.BigDecimal("110"), new java.math.BigDecimal("105"),
                        new java.math.BigDecimal("90"), 4.5, "TRIM", null, null, null,
                        new OrderTicket("TRIM", "AAA", 3, null, null, null),
                        true))); // targetReached
        String text = scheduler.render(OWNER, report);
        assertThat(text).contains("Ziel ✓").doesNotContain("Ziel 90");
    }

    @Test
    void skipsWhenNoHeldPositions() {
        HeldPositionService hp = mock(HeldPositionService.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of());

        scheduler(hp, svc, tg).run();

        verifyNoInteractions(tg);
    }

    @Test
    void depotDownSkipsSilentlyNoThrow() {
        // openPositions is fail-soft (empty on depot-down): the scheduler must treat this
        // exactly like "no held positions" -- no throw, no push.
        HeldPositionService hp = mock(HeldPositionService.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of());

        scheduler(hp, svc, tg).run();

        verifyNoInteractions(tg);
        verifyNoInteractions(svc);
    }

    @Test
    void skipsPushWhenAllPositionsHold() {
        HeldPositionService hp = mock(HeldPositionService.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of(held("AAA")));
        when(svc.build(OWNER)).thenReturn(
                new MorningReport("t", 0, 0, 1,
                        List.of(new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "HOLD", null, null, null,
                                new OrderTicket("HOLD", "AAA", 0, null, null, null), false))));

        scheduler(hp, svc, tg).run();

        verify(svc).build(OWNER);
        verify(tg, never()).notifyDigest(anyString());
    }

    @Test
    void pushesOnlyActionableLines() {
        HeldPositionService hp = mock(HeldPositionService.class);
        TelegramNotifier tg = mock(TelegramNotifier.class);
        MorningReportService svc = mock(MorningReportService.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of(held("AAA")));
        when(svc.build(OWNER)).thenReturn(
                new MorningReport("t", 1, 0, 1, List.of(
                        new MorningReportLine("BBB", "Bbb", 10, 100,
                                null, null, null, null, "SELL", null, null, null,
                                new OrderTicket("SELL", "BBB", 10, null, null, null), false),
                        new MorningReportLine("AAA", "Aaa", 10, 100,
                                null, null, null, null, "HOLD", null, null, null,
                                new OrderTicket("HOLD", "AAA", 0, null, null, null), false))));

        scheduler(hp, svc, tg).run();

        // pushes once, body lists the actionable SELL symbol but omits the HOLD symbol
        verify(tg).notifyDigest(argThat(s -> s.contains("BBB") && !s.contains("AAA")));
    }
}
