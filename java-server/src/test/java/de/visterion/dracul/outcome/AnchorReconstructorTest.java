package de.visterion.dracul.outcome;

import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorReconstructorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    private final AnchorReconstructor r = new AnchorReconstructor();

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    /** Weekday bars from start (inclusive) for n trading days. Alternating closes 100/110 with
     *  narrow ranges: every TR is 12 while every H-L is 4, so an implementation using H-L
     *  yields 4.0000 instead of 12.0000. */
    private static List<OhlcBar> alternating(LocalDate start, int n) {
        List<OhlcBar> out = new ArrayList<>();
        LocalDate d = start;
        int i = 0;
        while (out.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                boolean odd = i % 2 == 1;
                BigDecimal c = odd ? bd("110") : bd("100");
                BigDecimal h = odd ? bd("112") : bd("102");
                BigDecimal l = odd ? bd("108") : bd("98");
                out.add(new OhlcBar(d, c, h, l, c, 1000));
                i++;
            }
            d = d.plusDays(1);
        }
        return out;
    }

    private static LocalDate lastDate(List<OhlcBar> bars) { return bars.getLast().date(); }

    // --- cutoff -----------------------------------------------------------------------------

    @Test void nyAfterCloseUsesSameDay() {   // Tue 2026-09-15 17:03 EDT
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T21:03:00Z"), NY))
                .isEqualTo(LocalDate.parse("2026-09-15"));
    }
    @Test void nyCloseBoundaryIsHalfOpen() {
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T20:19:59Z"), NY))
                .isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T20:20:00Z"), NY))
                .isEqualTo(LocalDate.parse("2026-09-15"));
    }
    @Test void nyPreOpenUsesPreviousDay() {   // Tue 00:01 EDT = 04:01 UTC
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T04:01:00Z"), NY))
                .isEqualTo(LocalDate.parse("2026-09-14"));
    }
    @Test void nyWinterUsesEst() {             // Tue 2026-01-13 16:25 EST = 21:25 UTC
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-01-13T21:25:00Z"), NY))
                .isEqualTo(LocalDate.parse("2026-01-13"));
        // 20:30 UTC in January is 15:30 EST -> still in session
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-01-13T20:30:00Z"), NY))
                .isEqualTo(LocalDate.parse("2026-01-12"));
    }
    @Test void hkInSessionUsesPreviousDay() {  // Tue 14:01 HKT = 06:01 UTC
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T06:01:00Z"), HK))
                .isEqualTo(LocalDate.parse("2026-09-14"));
    }
    @Test void hkAfterCloseUsesSameDay() {   // Tue 2026-09-15 17:01 HKT = 09:01 UTC
        assertThat(AnchorReconstructor.cutoff(Instant.parse("2026-09-15T09:01:00Z"), HK))
                .isEqualTo(LocalDate.parse("2026-09-15"));
    }

    // --- anchor bar -------------------------------------------------------------------------

    @Test void emissionTimeDecidesNotDecisionTime() {
        // Emitted Tue 04:01 UTC (00:01 EDT, pre-open). Even though a decision might be taken
        // after the Tuesday close, the anchor must be Monday.
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 40); // ends after 09-15
        var res = r.reconstruct("TESTCO", Instant.parse("2026-09-15T04:01:00Z"), bars,
                LocalDate.parse("2026-07-01"));
        assertThat(res).isInstanceOf(AnchorReconstructor.Anchor.class);
        assertThat(((AnchorReconstructor.Anchor) res).barDate()).isEqualTo(LocalDate.parse("2026-09-14"));
    }
    @Test void mondayPreOpenAnchorsOnFriday() {
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 40);
        var res = r.reconstruct("TESTCO", Instant.parse("2026-09-14T04:01:00Z"), bars,
                LocalDate.parse("2026-07-01"));
        assertThat(((AnchorReconstructor.Anchor) res).barDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    }
    @Test void weekendAnchorsOnFriday() {
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 40);
        var sat = r.reconstruct("TESTCO", Instant.parse("2026-09-12T15:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        var sun = r.reconstruct("TESTCO", Instant.parse("2026-09-13T23:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        assertThat(((AnchorReconstructor.Anchor) sat).barDate()).isEqualTo(LocalDate.parse("2026-09-11"));
        assertThat(((AnchorReconstructor.Anchor) sun).barDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    }
    @Test void holidayGapTakesLastExistingBar() {
        List<OhlcBar> bars = new ArrayList<>(alternating(LocalDate.parse("2026-08-03"), 40));
        bars.removeIf(b -> b.date().equals(LocalDate.parse("2026-09-14"))); // "holiday" Monday
        var res = r.reconstruct("ALPHA.HK", Instant.parse("2026-09-15T06:01:00Z"), bars, LocalDate.parse("2026-07-01"));
        assertThat(((AnchorReconstructor.Anchor) res).barDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    }
    @Test void duplicateSameDateRowsLastWinsAndUnsortedInputIsSorted() {
        List<OhlcBar> bars = new ArrayList<>(alternating(LocalDate.parse("2026-08-03"), 23)); // ends 2026-09-02
        // bar[1] (2026-08-04) gets a distinctly wide range: it sits exactly at the ATR window's
        // oldest edge (window = last 22 TRs = series indices 1..22). A last-wins+sorted
        // implementation includes its TR (100) in the sum; an implementation that instead keeps
        // both same-date rows below (shifting the window by one) would drop it and pick up the
        // discarded real last-bar's ordinary TR (12) instead -> a different exact ATR either way.
        OhlcBar b1 = bars.get(1);
        bars.set(1, new OhlcBar(b1.date(), b1.open(), bd("200"), bd("190"), b1.close(), b1.volume()));
        OhlcBar last = bars.getLast();
        // a same-date snapshot row appended after the real bar, with a distinct wild range
        bars.add(new OhlcBar(last.date(), last.open(), bd("150"), bd("100"), bd("140"), 5));
        // unsorted input INSIDE the ATR window: swap two ordinary dated rows that both fall
        // inside it, without touching the duplicate-date pair's relative order (indices 22/23)
        java.util.Collections.swap(bars, 1, 10);
        var res = r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        var a = (AnchorReconstructor.Anchor) res;
        // last-wins keeps the wild row for that date
        assertThat(a.barDate()).isEqualTo(last.date());
        // exact: correct dedup (last-wins) + sort gives 17.7273; "keep both" rows would give
        // 13.7273 instead (verified by hand and by a Python cross-check during review)
        assertThat(a.atr()).isEqualByComparingTo(bd("17.7273"));
    }

    // --- ATR ----------------------------------------------------------------------------------

    @Test void atrIsSmaOfTrueRangeUsingPreviousCloseAtScale4() {
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 23);
        var res = r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        var a = (AnchorReconstructor.Anchor) res;
        assertThat(a.barDate()).isEqualTo(lastDate(bars));
        assertThat(a.atr()).isEqualByComparingTo(bd("12.0000"));
        assertThat(a.atr().scale()).isEqualTo(4);
    }
    @Test void windowIgnoresBarsAfterTheAnchor() {
        // the real production case: the provider's series extends past the anchor date (more
        // recent bars exist that were not yet complete at emission time). An implementation that
        // simply averages the last 22 TRs of the whole series -- instead of the 22 TRs ending at
        // the anchor -- would pick these up and fail this test.
        List<OhlcBar> bars = new ArrayList<>(alternating(LocalDate.parse("2026-08-03"), 23)); // ends 2026-09-02
        LocalDate afterAnchor = LocalDate.parse("2026-09-03");
        for (int i = 0; i < 5; i++) {
            bars.add(new OhlcBar(afterAnchor.plusDays(i), bd("100"), bd("900"), bd("1"), bd("100"), 1));
        }
        var a = (AnchorReconstructor.Anchor) r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars,
                LocalDate.parse("2026-07-01"));
        assertThat(a.barDate()).isEqualTo(LocalDate.parse("2026-09-02"));
        assertThat(a.atr()).isEqualByComparingTo(bd("12.0000"));
    }
    @Test void windowIgnoresOlderBars() {
        List<OhlcBar> bars = new ArrayList<>();
        // 5 wild bars first, then 23 alternating bars; the ATR window only sees the last 22 TRs
        LocalDate d = LocalDate.parse("2026-07-27");
        for (int i = 0; i < 5; i++) { bars.add(new OhlcBar(d.plusDays(i), bd("100"), bd("900"), bd("1"), bd("100"), 1)); }
        bars.addAll(alternating(LocalDate.parse("2026-08-03"), 23));
        var a = (AnchorReconstructor.Anchor) r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        assertThat(a.atr()).isEqualByComparingTo(bd("12.0000"));
    }
    @Test void roundsHalfUp() {
        // 22 TRs of 12 except the last which is 12.0011 -> mean exactly 12.00005 -> 12.0001 (HALF_UP).
        // Bar index 22 is an even bar (H 102, L 98, prev close 110): its TR is |L - prevClose|,
        // so lowering L by 0.0011 raises the TR to 12.0011.
        List<OhlcBar> bars = new ArrayList<>(alternating(LocalDate.parse("2026-08-03"), 23));
        OhlcBar l = bars.removeLast();
        bars.add(new OhlcBar(l.date(), l.open(), l.high(), l.low().subtract(bd("0.0011")), l.close(), 1));
        var a = (AnchorReconstructor.Anchor) r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars, LocalDate.parse("2026-07-01"));
        assertThat(a.atr()).isEqualByComparingTo(bd("12.0001"));
    }
    @Test void twentyTwoBarsFailTwentyThreeSucceed() {
        List<OhlcBar> bars22 = alternating(LocalDate.parse("2026-08-03"), 22);
        var f = r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars22, LocalDate.parse("2026-07-01"));
        assertThat(f).isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.TOO_FEW_BARS, true));
        List<OhlcBar> bars23 = alternating(LocalDate.parse("2026-08-03"), 23);
        assertThat(r.reconstruct("TESTCO", Instant.parse("2026-09-02T21:00:00Z"), bars23, LocalDate.parse("2026-07-01")))
                .isInstanceOf(AnchorReconstructor.Anchor.class);
    }
    @Test void tooFewBarsIsTransientWhenOurWindowWasTooShort() {
        // first bar is ON/BEFORE windowStart -> provider may have more history -> transient
        List<OhlcBar> bars22 = alternating(LocalDate.parse("2026-08-03"), 22);
        var f = r.reconstruct("TESTCO", Instant.parse("2026-09-01T21:00:00Z"), bars22, LocalDate.parse("2026-08-03"));
        assertThat(f).isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.TOO_FEW_BARS, false));
    }
    @Test void malformedBarInsideWindowFailsOutsideDoesNot() {
        // 30 bars, last (index 29) is the anchor; the malformed-check window is the 23 bars
        // ending at the anchor, i.e. indices 7..29 -- so index 6 is the pinned just-outside case
        // and index 7 is the pinned oldest-inside-the-window case.
        List<OhlcBar> bars = new ArrayList<>(alternating(LocalDate.parse("2026-08-03"), 30));
        OhlcBar old = bars.get(6);   // just outside the 23-wide window
        bars.set(6, new OhlcBar(old.date(), old.open(), BigDecimal.ZERO, old.low(), old.close(), 1));
        assertThat(r.reconstruct("TESTCO", Instant.parse("2026-09-11T21:00:00Z"), bars, LocalDate.parse("2026-07-01")))
                .isInstanceOf(AnchorReconstructor.Anchor.class);
        OhlcBar inside = bars.get(7);   // the oldest bar inside the window
        bars.set(7, new OhlcBar(inside.date(), inside.open(), inside.high(), inside.low(), BigDecimal.ZERO, 1));
        assertThat(r.reconstruct("TESTCO", Instant.parse("2026-09-11T21:00:00Z"), bars, LocalDate.parse("2026-07-01")))
                .isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.MALFORMED, true));
    }

    // --- staleness / venue / empty ------------------------------------------------------------

    @Test void staleAtSevenDaysPassesAtEightFails() {
        // 30 weekdays from Mon 2026-08-03 end on Fri 2026-09-11
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 30);
        assertThat(lastDate(bars)).isEqualTo(LocalDate.parse("2026-09-11"));
        // Fri 2026-09-18 17:00 EDT -> cutoff 09-18 -> 7 days behind -> ok
        Instant seven = Instant.parse("2026-09-18T21:00:00Z");
        // Sun 2026-09-20 -> cutoff Sat 09-19 -> 8 days behind -> stale
        Instant eight = Instant.parse("2026-09-20T15:00:00Z");
        assertThat(r.reconstruct("TESTCO", seven, bars, LocalDate.parse("2026-07-01")))
                .isInstanceOf(AnchorReconstructor.Anchor.class);
        assertThat(r.reconstruct("TESTCO", eight, bars, LocalDate.parse("2026-07-01")))
                .isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.STALE, true));
    }
    @Test void unsupportedVenues() {
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-08-03"), 30);
        var f = new AnchorReconstructor.Failure(AnchorReconstructor.UNSUPPORTED_VENUE, true);
        assertThat(r.reconstruct("TESTCO.L", Instant.parse("2026-12-01T00:00:00Z"), bars, LocalDate.parse("2026-07-01"))).isEqualTo(f);
        assertThat(r.reconstruct("US0000000001", Instant.parse("2026-12-01T00:00:00Z"), bars, LocalDate.parse("2026-07-01"))).isEqualTo(f);
        assertThat(AnchorReconstructor.venueZone("ALPHA.HK")).isEqualTo(HK);
        assertThat(AnchorReconstructor.venueZone("alpha.hk")).isEqualTo(HK);
        assertThat(AnchorReconstructor.venueZone(" TESTCO ")).isEqualTo(NY);
    }
    @Test void emptySeriesIsTransient() {
        assertThat(r.reconstruct("TESTCO", Instant.parse("2026-12-01T00:00:00Z"), List.of(), LocalDate.parse("2026-07-01")))
                .isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.NO_DATA, false));
    }
    @Test void noBarBeforeEmissionIsPermanentOnlyForGenuinelyShortHistory() {
        List<OhlcBar> bars = alternating(LocalDate.parse("2026-09-01"), 30);
        Instant before = Instant.parse("2026-08-20T21:00:00Z");
        assertThat(r.reconstruct("TESTCO", before, bars, LocalDate.parse("2026-07-01")))
                .isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.NO_BAR_BEFORE_EMISSION, true));
        assertThat(r.reconstruct("TESTCO", before, bars, LocalDate.parse("2026-09-01")))
                .isEqualTo(new AnchorReconstructor.Failure(AnchorReconstructor.NO_BAR_BEFORE_EMISSION, false));
    }
}
