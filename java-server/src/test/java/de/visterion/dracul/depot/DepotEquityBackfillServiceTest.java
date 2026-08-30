package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The backwards pass. Prices and FX come from stubs so the arithmetic is provable to the
 * cent; the seam check — "does the reconstruction land on the measured value" — is the same
 * assertion the production run makes against prod.
 */
class DepotEquityBackfillServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final Instant ANCHOR = Instant.parse("2026-03-05T00:00:00Z");
    private static final LocalDate D2 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 4);
    private static final LocalDate D4 = LocalDate.of(2026, 3, 5);

    /** Bars for one symbol: same close every day keeps the arithmetic checkable by hand. */
    private static JsonNode bars(Map<LocalDate, String> closes) {
        var root = M.createObjectNode();
        var arr = root.putArray("bars");
        closes.keySet().stream().sorted().forEach(d -> {
            var b = arr.addObject();
            b.put("date", d.toString());
            b.put("close", closes.get(d));
        });
        return root;
    }

    private record Fixture(DepotEquityBackfillService service,
                           DepotEquitySnapshotRepository repo) {
    }

    private static Fixture fixture(List<BookPosition> book,
                                   Map<String, BigDecimal> brokerQty,
                                   Map<LocalDate, String> priceAAA,
                                   Map<LocalDate, String> fx,
                                   BigDecimal anchorEquity,
                                   BigDecimal anchorCash) {
        var repo = mock(DepotEquitySnapshotRepository.class);
        var source = mock(BackfillSourceRepository.class);
        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        var depotClient = mock(AgoraDepotClient.class);

        when(repo.firstMeasured("c1", "DAILY")).thenReturn(Optional.of(
                new DepotEquitySnapshot(1L, "c1", ANCHOR, "DAILY", anchorEquity, anchorCash,
                        anchorEquity.subtract(anchorCash), "EUR", BigDecimal.ZERO, "MEASURED")));
        when(repo.upsertReconstructed(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));
        when(source.bookPositions("c1")).thenReturn(book);

        List<DepotPosition> brokerPositions = brokerQty.entrySet().stream()
                .map(e -> new DepotPosition(e.getKey(), null, e.getValue(), null, null, null,
                        "USD", null, null))
                .toList();
        when(depotClient.positions("c1"))
                .thenReturn(new PositionsSnapshot(brokerPositions, null));

        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> {
            String symbol = inv.getArgument(1, JsonNode.class).path("symbol").asString();
            return "EURUSD=X".equals(symbol) ? bars(fx) : bars(priceAAA);
        });

        return new Fixture(
                new DepotEquityBackfillService(repo, source, agora, depotClient), repo);
    }

    private static BookPosition open(String symbol, String qty, String price, LocalDate entry) {
        return new BookPosition(1L, symbol, "OPEN", new BigDecimal(qty), new BigDecimal(price),
                entry, null, null, new BigDecimal(qty), null);
    }

    @Test
    void reconstructionLandsOnTheMeasuredValueAtTheAnchor() {
        // 10 shares at 20 USD, FX 2.0 -> 100 EUR of stock, cash 400 EUR, equity 500 EUR.
        var f = fixture(
                List.of(open("AAA", "10", "20.00", D2)),
                Map.of("AAA", new BigDecimal("10")),
                Map.of(D2, "20.00", D3, "20.00", D4, "20.00"),
                Map.of(D2, "2.0", D3, "2.0", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        var report = f.service().run("c1");

        assertThat(report.seamDelta()).isEqualByComparingTo("0.00");
    }

    @Test
    void aBuyDoesNotMoveEquity() {
        // The purchase on D3 moves 100 EUR from cash to stock. Equity on D2 (before) must
        // equal equity on D3 (after) when the price does not move.
        var f = fixture(
                List.of(open("AAA", "10", "20.00", D3)),
                Map.of("AAA", new BigDecimal("10")),
                Map.of(D2, "20.00", D3, "20.00", D4, "20.00"),
                Map.of(D2, "2.0", D3, "2.0", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        f.service().run("c1");

        var written = writtenEquities(f.repo());
        assertThat(written.get(D2)).isEqualByComparingTo("500.00");
        assertThat(written.get(D3)).isEqualByComparingTo("500.00");
    }

    @Test
    void priceAndFxAreAppliedPerDayNotOnce() {
        // FX also varies (D3 = 2.5, everywhere else 2.0) so a lookup pinned to the wrong day
        // — anchorDay instead of d, for either the price or the FX rate — changes the result.
        // A constant FX across the whole fixture would make an "at(fx, d) -> at(fx, anchorDay)"
        // mutation a no-op regardless of how the production code is written.
        var f = fixture(
                List.of(open("AAA", "10", "20.00", D2)),
                Map.of("AAA", new BigDecimal("10")),
                Map.of(D2, "20.00", D3, "30.00", D4, "20.00"),
                Map.of(D2, "2.0", D3, "2.5", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        f.service().run("c1");

        var written = writtenEquities(f.repo());
        assertThat(written.get(D2)).isEqualByComparingTo("500.00");   // 400 + 200/2.0
        assertThat(written.get(D3)).isEqualByComparingTo("520.00");   // 400 + 300/2.5
    }

    @Test
    void aDayWithoutABarUsesThePrecedingClose() {
        // D1 predates AAA's first ever bar: no preceding close exists at all there, and that
        // (not D3's gap) is what must land in missingBars — a hole, not a carried-forward day.
        // D3 has no bar of its own but D2's close covers it via floorEntry, which is exactly the
        // "not an error" half of the rule and must NOT show up in missingBars.
        LocalDate d1 = LocalDate.of(2026, 3, 2);
        var f = fixture(
                List.of(open("AAA", "10", "20.00", d1)),
                Map.of("AAA", new BigDecimal("10")),
                Map.of(D2, "20.00", D4, "20.00"),          // D1 and D3 have no AAA bar
                Map.of(d1, "2.0", D2, "2.0", D3, "2.0", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        var report = f.service().run("c1");

        assertThat(report.missingBars()).anySatisfy(s -> assertThat(s).contains("AAA"));
        // The carried-forward day must NOT be reported as a hole — that is the other half of
        // the rule this test exists to pin.
        assertThat(report.missingBars()).noneMatch(s -> s.contains("@" + D3));
        assertThat(writtenEquities(f.repo()).get(D3)).isEqualByComparingTo("500.00");
        // d1 has no preceding close at all, so the day must be skipped entirely rather than
        // written with a partial equity that silently drops the unpriced holding.
        assertThat(writtenEquities(f.repo())).doesNotContainKey(d1);
    }

    @Test
    void missingEnterRowIsAConflictNotAGuess() {
        var noEnter = new BookPosition(1L, "AAA", "OPEN", new BigDecimal("10"),
                new BigDecimal("20.00"), D2, null, null, null, null);
        var f = fixture(List.of(noEnter), Map.of("AAA", new BigDecimal("10")),
                Map.of(D2, "20.00"), Map.of(D2, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        assertThatThrownBy(() -> f.service().run("c1"))
                .isInstanceOf(DepotEquityBackfillService.BackfillConflictException.class)
                .hasMessageContaining("AAA");
    }

    @Test
    void noMeasuredRowIsAConflict() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.firstMeasured("c1", "DAILY")).thenReturn(Optional.empty());
        var service = new DepotEquityBackfillService(repo, mock(BackfillSourceRepository.class),
                mock(de.visterion.dracul.marketdata.AgoraClient.class), mock(AgoraDepotClient.class));

        assertThatThrownBy(() -> service.run("c1"))
                .isInstanceOf(DepotEquityBackfillService.BackfillConflictException.class)
                .hasMessageContaining("no measured");
    }

    @Test
    void weekendsProduceNoPoints() {
        // Trading days come from the bar dates, so a Saturday/Sunday can only appear if the
        // code iterates the calendar instead of the fetched bars. The anchor is deliberately a
        // Monday and the entry a Thursday so a weekend actually falls inside the window — a
        // bar-driven implementation naturally skips it (no OHLC provider quotes a weekend), a
        // calendar-driven one would not.
        Instant weekendAnchor = Instant.parse("2026-03-09T00:00:00Z");           // Monday
        LocalDate thu = LocalDate.of(2026, 3, 5);
        LocalDate fri = LocalDate.of(2026, 3, 6);
        LocalDate mon = LocalDate.of(2026, 3, 9);

        var repo = mock(DepotEquitySnapshotRepository.class);
        var source = mock(BackfillSourceRepository.class);
        var agora = mock(de.visterion.dracul.marketdata.AgoraClient.class);
        var depotClient = mock(AgoraDepotClient.class);

        when(repo.firstMeasured("c1", "DAILY")).thenReturn(Optional.of(
                new DepotEquitySnapshot(1L, "c1", weekendAnchor, "DAILY", new BigDecimal("500.00"),
                        new BigDecimal("400.00"), new BigDecimal("100.00"), "EUR",
                        BigDecimal.ZERO, "MEASURED")));
        when(repo.upsertReconstructed(anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.of(new DepotEquitySnapshotRepository.SnapshotWrite(1L, true)));
        when(source.bookPositions("c1")).thenReturn(List.of(open("AAA", "10", "20.00", thu)));
        when(depotClient.positions("c1")).thenReturn(new PositionsSnapshot(
                List.of(new DepotPosition("AAA", null, new BigDecimal("10"), null, null, null,
                        "USD", null, null)), null));
        // No Saturday/Sunday bar — real OHLC data never quotes a weekend.
        when(agora.callTool(eq("get_ohlc"), any())).thenAnswer(inv -> {
            String symbol = inv.getArgument(1, JsonNode.class).path("symbol").asString();
            return "EURUSD=X".equals(symbol)
                    ? bars(Map.of(thu, "2.0", fri, "2.0", mon, "2.0"))
                    : bars(Map.of(thu, "20.00", fri, "20.00", mon, "20.00"));
        });

        var service = new DepotEquityBackfillService(repo, source, agora, depotClient);
        service.run("c1");

        assertThat(writtenEquities(repo).keySet())
                .allSatisfy(d -> assertThat(d.getDayOfWeek().getValue()).isLessThan(6));
    }

    @Test
    void positionsAfterTheAnchorDoNotShiftTheCurve() {
        // BackfillSourceRepository.bookPositions has no date filter, so the book can contain a
        // position opened after the anchor. Its cash is already outside anchor.cash() (the
        // anchor is a snapshot of that one day, not of the account's future) and must not be
        // added again to every reconstructed day, or to the seam check itself.
        LocalDate afterAnchor = LocalDate.of(2026, 3, 10);
        var f = fixture(
                List.of(open("AAA", "10", "20.00", D2), open("BBB", "5", "50.00", afterAnchor)),
                Map.of("AAA", new BigDecimal("10"), "BBB", new BigDecimal("5")),
                Map.of(D2, "20.00", D3, "20.00", D4, "20.00"),
                Map.of(D2, "2.0", D3, "2.0", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        var report = f.service().run("c1");

        assertThat(report.seamDelta()).isEqualByComparingTo("0.00");
        var written = writtenEquities(f.repo());
        assertThat(written.get(D2)).isEqualByComparingTo("500.00");
        assertThat(written.get(D3)).isEqualByComparingTo("500.00");
    }

    @Test
    void reconstructionDoesNotReachBeyondOneDayBeforeTheFirstEntry() {
        // The fetched window reaches far back (400 days in production); the reconstruction
        // must still stop one trading day before the first purchase, not wherever the bar data
        // happens to start. Everything earlier would be a flat line asserting the account
        // already held its full capital long before it existed.
        LocalDate farBack = LocalDate.of(2026, 2, 20);
        var f = fixture(
                List.of(open("AAA", "10", "20.00", D3)),
                Map.of("AAA", new BigDecimal("10")),
                Map.of(farBack, "20.00", D2, "20.00", D3, "20.00", D4, "20.00"),
                Map.of(farBack, "2.0", D2, "2.0", D3, "2.0", D4, "2.0"),
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        f.service().run("c1");

        var written = writtenEquities(f.repo());
        assertThat(written).doesNotContainKey(farBack);
        assertThat(written.keySet()).allSatisfy(d -> assertThat(d).isAfterOrEqualTo(D2));
    }

    @Test
    void aThirdCurrencyAbortsInsteadOfAssumingUsd() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.firstMeasured("c1", "DAILY")).thenReturn(Optional.of(
                new DepotEquitySnapshot(1L, "c1", ANCHOR, "DAILY", new BigDecimal("500.00"),
                        new BigDecimal("400.00"), new BigDecimal("100.00"), "EUR",
                        BigDecimal.ZERO, "MEASURED")));
        var source = mock(BackfillSourceRepository.class);
        when(source.bookPositions("c1"))
                .thenReturn(List.of(open("AAA", "10", "20.00", D2)));
        var depotClient = mock(AgoraDepotClient.class);
        when(depotClient.positions("c1")).thenReturn(new PositionsSnapshot(
                List.of(new DepotPosition("AAA", null, new BigDecimal("10"), null, null, null,
                        "JPY", null, null)), null));
        var service = new DepotEquityBackfillService(repo, source,
                mock(de.visterion.dracul.marketdata.AgoraClient.class), depotClient);

        assertThatThrownBy(() -> service.run("c1"))
                .isInstanceOf(DepotEquityBackfillService.BackfillConflictException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    void connectionWithoutPositionsWritesNothing() {
        var f = fixture(List.of(), Map.of(), Map.of(), Map.of(),
                new BigDecimal("500.00"), new BigDecimal("500.00"));

        var report = f.service().run("c1");

        assertThat(report.daysWritten()).isZero();
    }

    private static Map<LocalDate, BigDecimal> writtenEquities(DepotEquitySnapshotRepository repo) {
        var cap = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var eq = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce())
                .upsertReconstructed(anyString(), cap.capture(), anyString(), eq.capture(),
                        any(), anyString());
        var out = new java.util.HashMap<LocalDate, BigDecimal>();
        for (int i = 0; i < cap.getAllValues().size(); i++) {
            out.put(cap.getAllValues().get(i).atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                    eq.getAllValues().get(i));
        }
        return out;
    }
}
