package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static de.visterion.dracul.benchmark.BenchmarkFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;

class OutcomeCalculatorTest {

    private static final PriceSeries EUNL = series(d(2026, 1, 5), d(2026, 6, 30), "100",
            Map.of(d(2026, 3, 30), "102"));
    private static final TradingCalendar CAL = EUNL.calendar();

    /** Sunday evening reference → entry Monday 2026-03-02; h=20 exits 2026-03-30. */
    private static MeasureEvent signal(String symbol, Offer offer) {
        LocalDate start = d(2026, 3, 1);
        return new MeasureEvent(EventKind.SIGNAL, "strigoi-syna", symbol, start,
                Instant.parse("2026-03-01T22:00:00Z"), null, start, offer);
    }

    private static BenchmarkOutcome complete(OutcomeCalculator.Result r) {
        assertThat(r).isInstanceOf(OutcomeCalculator.Complete.class);
        return ((OutcomeCalculator.Complete) r).row();
    }

    /** Spec §6 test 1: .HK candidate with HKD bars converts via HKDEUR=X. */
    @Test
    void hkdInstrumentIsConvertedWithTheFxCloseBeforeEntryAndOnTheExitDate() {
        PriceSeries hk = series(d(2026, 2, 2), d(2026, 4, 30), "10", Map.of(d(2026, 3, 30), "11"));
        PriceSeries hkdEur = series(d(2026, 1, 5), d(2026, 6, 30), "0.11", Map.of(d(2026, 3, 30), "0.12"));

        BenchmarkOutcome row = complete(OutcomeCalculator.compute(signal("SYN3.HK", null), 20, hk, "HKD",
                EUNL, CAL, hkdEur));

        assertThat(row.exitKind()).isEqualTo(ExitKind.NORMAL);
        assertThat(row.entryDate()).isEqualTo(d(2026, 3, 2));
        assertThat(row.entryPrice()).isEqualByComparingTo("10");
        assertThat(row.exitDate()).isEqualTo(d(2026, 3, 30));
        assertThat(row.exitPrice()).isEqualByComparingTo("11");
        assertThat(row.currency()).isEqualTo("HKD");
        assertThat(row.fxEntry()).isEqualByComparingTo("0.11");
        assertThat(row.fxExit()).isEqualByComparingTo("0.12");
        assertThat(row.returnEur()).isEqualByComparingTo("0.2");
        assertThat(row.benchmarkReturnEur()).isEqualByComparingTo("0.02");
        assertThat(row.excess()).isEqualByComparingTo("0.18");
        assertThat(row.horizonDays()).isEqualTo(20);
        assertThat(row.kind()).isEqualTo(EventKind.SIGNAL);
        assertThat(row.picked()).isNull();
        assertThat(row.entryRefDate()).isEqualTo(d(2026, 3, 1));
    }

    /** Final review M1: compute applies the HKD 01:30 UTC cutoff — a 05:00 UTC reference on a
     *  Monday enters Tuesday's open, while a USD reference at the same instant enters Monday. */
    @Test
    void theEntryCutoffFollowsTheInstrumentCurrency() {
        PriceSeries bars = series(d(2026, 2, 2), d(2026, 4, 30), "10");
        PriceSeries fx = series(d(2026, 1, 5), d(2026, 6, 30), "0.11");
        MeasureEvent at0500 = new MeasureEvent(EventKind.SIGNAL, "strigoi-syna", "SYN3.HK", d(2026, 3, 2),
                Instant.parse("2026-03-02T05:00:00Z"), null, d(2026, 3, 2), null);

        BenchmarkOutcome hkd = complete(OutcomeCalculator.compute(at0500, 20, bars, "HKD", EUNL, CAL, fx));
        BenchmarkOutcome usd = complete(OutcomeCalculator.compute(at0500, 20, bars, "USD", EUNL, CAL, fx));

        assertThat(hkd.entryDate()).isEqualTo(d(2026, 3, 3));
        assertThat(usd.entryDate()).isEqualTo(d(2026, 3, 2));
    }

    @Test
    void eurInstrumentNeedsNoFx() {
        PriceSeries de = series(d(2026, 2, 2), d(2026, 4, 30), "20", Map.of(d(2026, 3, 30), "21"));
        BenchmarkOutcome row = complete(OutcomeCalculator.compute(signal("SYN2.DE", null), 20, de, "EUR",
                EUNL, CAL, null));
        assertThat(row.fxEntry()).isEqualByComparingTo("1");
        assertThat(row.returnEur()).isEqualByComparingTo("0.05");
        assertThat(row.excess()).isEqualByComparingTo("0.03");
    }

    /** Spec §6 test 5: prey after the instrument's last bar → NO_ENTRY, not pending forever. */
    @Test
    void noBarAfterTheReferenceWithTenEunlDaysIsATerminalNoEntry() {
        PriceSeries dead = series(d(2026, 1, 5), d(2026, 2, 27), "10");
        MeasureEvent late = new MeasureEvent(EventKind.SIGNAL, "strigoi-syna", "SYN1", d(2026, 3, 10),
                Instant.parse("2026-03-10T22:00:00Z"), null, d(2026, 3, 10), null);

        BenchmarkOutcome row = complete(OutcomeCalculator.compute(late, 60, dead, "USD", EUNL, CAL,
                series(d(2026, 1, 5), d(2026, 6, 30), "0.9")));

        assertThat(row.exitKind()).isEqualTo(ExitKind.NO_ENTRY);
        assertThat(row.excess()).isNull();
        assertThat(row.entryDate()).isNull();
        assertThat(row.currency()).isEqualTo("USD");
    }

    @Test
    void noBarAfterTheReferenceWithFewerThanTenEunlDaysIsStillPending() {
        PriceSeries dead = series(d(2026, 1, 5), d(2026, 2, 27), "10");
        PriceSeries shortEunl = series(d(2026, 1, 5), d(2026, 3, 20), "100");
        MeasureEvent late = new MeasureEvent(EventKind.SIGNAL, "strigoi-syna", "SYN1", d(2026, 3, 10),
                Instant.parse("2026-03-10T22:00:00Z"), null, d(2026, 3, 10), null);
        assertThat(OutcomeCalculator.compute(late, 20, dead, "USD", shortEunl, shortEunl.calendar(),
                series(d(2026, 1, 5), d(2026, 6, 30), "0.9")))
                .isInstanceOf(OutcomeCalculator.Pending.class);
    }

    /** Spec §6 test 6: 30 bars then none, h=60 → delisted via the EUNL calendar. */
    @Test
    void thirtyBarsThenNoneIsDelistedAtTheOfferOrTheLastClose() {
        PriceSeries thirty = series(d(2026, 3, 2), d(2026, 4, 10), "10");
        PriceSeries usd = series(d(2026, 1, 5), d(2026, 6, 30), "0.9");

        BenchmarkOutcome h20 = complete(OutcomeCalculator.compute(signal("SYN1", new Offer(new BigDecimal("15"), null)),
                20, thirty, "USD", EUNL, CAL, usd));
        assertThat(h20.exitKind()).isEqualTo(ExitKind.NORMAL);

        BenchmarkOutcome offer = complete(OutcomeCalculator.compute(signal("SYN1", new Offer(new BigDecimal("15"), null)),
                60, thirty, "USD", EUNL, CAL, usd));
        assertThat(offer.exitKind()).isEqualTo(ExitKind.DELISTED_OFFER);
        assertThat(offer.exitDate()).isEqualTo(d(2026, 4, 10));
        assertThat(offer.exitPrice()).isEqualByComparingTo("15");
        assertThat(offer.returnEur()).isEqualByComparingTo("0.5");

        BenchmarkOutcome last = complete(OutcomeCalculator.compute(signal("SYN1", null),
                60, thirty, "USD", EUNL, CAL, usd));
        assertThat(last.exitKind()).isEqualTo(ExitKind.DELISTED_LAST);
        assertThat(last.exitPrice()).isEqualByComparingTo("10");

        BenchmarkOutcome foreignOffer = complete(OutcomeCalculator.compute(
                signal("SYN1", new Offer(new BigDecimal("15"), "EUR")), 60, thirty, "USD", EUNL, CAL, usd));
        assertThat(foreignOffer.exitKind()).isEqualTo(ExitKind.DELISTED_LAST);
    }

    @Test
    void delistingWaitsForTheEunlHorizonDate() {
        PriceSeries thirty = series(d(2026, 3, 2), d(2026, 4, 10), "10");
        PriceSeries shortEunl = series(d(2026, 1, 5), d(2026, 5, 1), "100");
        assertThat(OutcomeCalculator.compute(signal("SYN1", null), 60, thirty, "USD", shortEunl,
                shortEunl.calendar(), series(d(2026, 1, 5), d(2026, 6, 30), "0.9")))
                .isInstanceOf(OutcomeCalculator.Pending.class);
    }

    @Test
    void tooFewBarsWhileTheInstrumentStillTradesIsPending() {
        PriceSeries live = series(d(2026, 3, 2), d(2026, 5, 20), "10");
        PriceSeries eunlToMay = series(d(2026, 1, 5), d(2026, 5, 29), "100");
        assertThat(OutcomeCalculator.compute(signal("SYN1", null), 60, live, "USD", eunlToMay,
                eunlToMay.calendar(), series(d(2026, 1, 5), d(2026, 6, 30), "0.9")))
                .isInstanceOf(OutcomeCalculator.Pending.class);
    }

    @Test
    void missingFxIsPendingNotAGuess() {
        PriceSeries usdBars = series(d(2026, 2, 2), d(2026, 4, 30), "10");
        assertThat(OutcomeCalculator.compute(signal("SYN1", null), 20, usdBars, "USD", EUNL, CAL,
                PriceSeries.empty())).isInstanceOf(OutcomeCalculator.Pending.class);
        assertThat(OutcomeCalculator.compute(signal("SYN1", null), 20, usdBars, "USD", EUNL, CAL, null))
                .isInstanceOf(OutcomeCalculator.Pending.class);
    }

    @Test
    void pickedCandidateRowKeepsItsEventStartAndPickedFlag() {
        MeasureEvent picked = new MeasureEvent(EventKind.CANDIDATE, "strigoi-merger", "SYN1", d(2026, 3, 4),
                Instant.parse("2026-03-01T22:00:00Z"), true, d(2026, 3, 6), null);
        BenchmarkOutcome row = complete(OutcomeCalculator.compute(picked, 20,
                series(d(2026, 2, 2), d(2026, 4, 30), "10"), "USD", EUNL, CAL,
                series(d(2026, 1, 5), d(2026, 6, 30), "0.9")));
        assertThat(row.kind()).isEqualTo(EventKind.CANDIDATE);
        assertThat(row.picked()).isTrue();
        assertThat(row.eventStartDate()).isEqualTo(d(2026, 3, 4));
        assertThat(row.entryRefDate()).isEqualTo(d(2026, 3, 1));
    }
}
