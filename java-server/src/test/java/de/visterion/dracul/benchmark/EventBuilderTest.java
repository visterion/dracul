package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

class EventBuilderTest {

    private static final String MERGER = "strigoi-merger";
    private final TradingCalendar cal =
            BenchmarkFixtures.series(d(2025, 10, 1), d(2026, 9, 30), "100").calendar();

    private static PreyRow prey(String agent, String symbol, String iso) {
        return new PreyRow(agent, symbol, Instant.parse(iso));
    }

    private static CandidateRow cand(String symbol, LocalDate day) {
        return new CandidateRow(MERGER, symbol, day.atTime(5, 0).toInstant(ZoneOffset.UTC), day, null, null);
    }

    private static List<CandidateRow> daily(String symbol, LocalDate from, LocalDate to) {
        List<CandidateRow> out = new ArrayList<>();
        BenchmarkFixtures.weekdayBars(from, to, "1", Map.of()).forEach(b -> out.add(cand(symbol, b.date())));
        return out;
    }

    @Test
    void repeatedPreyWithin60TradingDaysIsOneSignalEvent() {
        LocalDate d0 = d(2026, 3, 2);
        LocalDate plus60 = cal.plus(d0, 60).orElseThrow();
        List<MeasureEvent> events = EventBuilder.signalEvents(List.of(
                prey(MERGER, "SYNA", "2026-03-02T22:00:00Z"),
                prey(MERGER, "SYNA", "2026-03-20T22:00:00Z"),
                prey(MERGER, "SYNA", plus60 + "T22:00:00Z")), cal, Map.of());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().refAt()).isEqualTo(Instant.parse("2026-03-02T22:00:00Z"));
        assertThat(events.getFirst().kind()).isEqualTo(EventKind.SIGNAL);
        assertThat(events.getFirst().picked()).isNull();
        assertThat(events.getFirst().eventStart()).isEqualTo(d0);
    }

    @Test
    void aPreyMoreThan60TradingDaysLaterStartsANewSignalEvent() {
        LocalDate plus61 = cal.plus(d(2026, 3, 2), 61).orElseThrow();
        List<MeasureEvent> events = EventBuilder.signalEvents(List.of(
                prey(MERGER, "SYNA", "2026-03-02T22:00:00Z"),
                prey(MERGER, "SYNA", plus61 + "T22:00:00Z")), cal, Map.of());
        assertThat(events).hasSize(2);
    }

    @Test
    void signalEventsAreKeyedByAgentAndSymbolAndCarryTheLatestOffer() {
        Map<String, Offer> offers = EventBuilder.latestOffers(List.of(
                new CandidateRow(MERGER, "SYNA", Instant.parse("2026-03-01T05:00:00Z"), d(2026, 3, 1),
                        new BigDecimal("50"), null),
                new CandidateRow(MERGER, "SYNA", Instant.parse("2026-03-05T05:00:00Z"), d(2026, 3, 5),
                        new BigDecimal("52"), null),
                new CandidateRow(MERGER, "SYNA", Instant.parse("2026-03-06T05:00:00Z"), d(2026, 3, 6), null, null)));
        List<MeasureEvent> events = EventBuilder.signalEvents(List.of(
                prey(MERGER, "SYNA", "2026-03-02T22:00:00Z"),
                prey("strigoi-spin", "SYNA", "2026-03-02T22:00:00Z")), cal, offers);
        assertThat(events).hasSize(2);
        MeasureEvent merger = events.stream().filter(e -> e.agent().equals(MERGER)).findFirst().orElseThrow();
        assertThat(merger.offer().price()).isEqualByComparingTo("52");
    }

    /** Spec §6 test 3: candidate D0..D0+40, prey D0+38 → picked, measured from the prey. */
    @Test
    void aPreyNearTheEndOfARunPicksItAndIsTheReference() {
        List<MeasureEvent> events = EventBuilder.candidateEvents(daily("SYNA", d(2026, 3, 2), d(2026, 4, 10)),
                List.of(prey(MERGER, "SYNA", "2026-04-09T22:00:00Z")), cal);
        assertThat(events).hasSize(1);
        MeasureEvent e = events.getFirst();
        assertThat(e.picked()).isTrue();
        assertThat(e.refAt()).isEqualTo(Instant.parse("2026-04-09T22:00:00Z"));
        assertThat(e.eventStart()).isEqualTo(d(2026, 3, 2));
        assertThat(e.lastAppearance()).isEqualTo(d(2026, 4, 10));
    }

    /** Spec §6 test 4: prey D0−20, candidate first at D0 → exactly one picked event. */
    @Test
    void aPreyBeforeTheFirstAppearanceStillPicksExactlyOneEvent() {
        List<MeasureEvent> events = EventBuilder.candidateEvents(daily("SYNA", d(2026, 3, 2), d(2026, 3, 6)),
                List.of(prey(MERGER, "SYNA", "2026-02-10T22:00:00Z")), cal);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().picked()).isTrue();
        assertThat(events.getFirst().refAt()).isEqualTo(Instant.parse("2026-02-10T22:00:00Z"));
    }

    /** A prey of another symbol, another agent, or after the +5-day window does not pick: the event
     *  is rejected and measured from its first appearance. (Spec §6 test 2's event side — same symbol
     *  picks — is aPreyNearTheEndOfARunPicksItAndIsTheReference; its extraction side is in
     *  ScreenItemMapperTest.) */
    @Test
    void withoutAMatchingPreyTheEventIsRejectedAndMeasuredFromTheFirstAppearance() {
        List<MeasureEvent> events = EventBuilder.candidateEvents(daily("SYNA", d(2026, 3, 2), d(2026, 3, 6)),
                List.of(prey(MERGER, "SYNB", "2026-03-03T22:00:00Z"),
                        prey("strigoi-spin", "SYNA", "2026-03-03T22:00:00Z"),
                        prey(MERGER, "SYNA", "2026-03-20T22:00:00Z")), cal);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().picked()).isFalse();
        assertThat(events.getFirst().refAt()).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
    }

    @Test
    void aGapOfMoreThan60TradingDaysSplitsCandidateEvents() {
        LocalDate d0 = d(2026, 3, 2);
        assertThat(EventBuilder.candidateEvents(
                List.of(cand("SYNA", d0), cand("SYNA", cal.plus(d0, 60).orElseThrow())), List.of(), cal)).hasSize(1);
        assertThat(EventBuilder.candidateEvents(
                List.of(cand("SYNA", d0), cand("SYNA", cal.plus(d0, 61).orElseThrow())), List.of(), cal)).hasSize(2);
    }

    @Test
    void onePreyPicksAtMostOneEvent() {
        LocalDate d0 = d(2026, 3, 2);
        LocalDate second = cal.plus(d0, 61).orElseThrow();
        // 2026-03-05 lies in event 1's window AND in event 2's [second − 60 TD, …] window.
        List<MeasureEvent> events = EventBuilder.candidateEvents(
                List.of(cand("SYNA", d0), cand("SYNA", second)),
                List.of(prey(MERGER, "SYNA", "2026-03-05T22:00:00Z")), cal);
        assertThat(events).extracting(MeasureEvent::picked).containsExactly(true, false);
    }

    @Test
    void rejectedEventsSettleFiveCalendarDaysAfterTheLastAppearanceOnlyOnceTheGapRuleAlsoAllowsIt() {
        // Ruling F2: 5 calendar days alone is no longer enough once the 60-trading-day gap rule
        // could still extend the event (the 60-TD gap is ~84 calendar days, so the 5-day boundary
        // on its own is never sufficient in practice — settle needs BOTH conditions).
        MeasureEvent rejected = EventBuilder.candidateEvents(
                List.of(cand("SYNA", d(2026, 6, 26))), List.of(), cal).getFirst();
        assertThat(rejected.settledOn(d(2026, 7, 1), cal)).isFalse();
        assertThat(rejected.settledOn(d(2026, 7, 2), cal)).isFalse();
        MeasureEvent picked = EventBuilder.candidateEvents(List.of(cand("SYNA", d(2026, 6, 26))),
                List.of(prey(MERGER, "SYNA", "2026-06-26T22:00:00Z")), cal).getFirst();
        assertThat(picked.settledOn(d(2026, 6, 27), cal)).isTrue();
    }

    /** Ruling F2: settle only once the 60-trading-day gap rule can no longer extend the event. */
    @Test
    void rejectedEventSettlesOnlyAfterTheSixtyTradingDayGapHasPassed() {
        LocalDate lastAppearance = d(2026, 6, 26);
        MeasureEvent rejected = EventBuilder.candidateEvents(
                List.of(cand("SYNA", lastAppearance)), List.of(), cal).getFirst();

        LocalDate day10 = cal.plus(lastAppearance, 10).orElseThrow();
        assertThat(rejected.settledOn(day10, cal)).isFalse();

        // Boundary: exactly 60 trading days later the gap rule (> 60) could still extend the event.
        LocalDate day60 = cal.plus(lastAppearance, 60).orElseThrow();
        assertThat(cal.countBetween(lastAppearance, day60)).isEqualTo(60);
        assertThat(rejected.settledOn(day60, cal)).isFalse();

        LocalDate day61 = cal.plus(lastAppearance, 61).orElseThrow();
        assertThat(day61.isAfter(lastAppearance.plusDays(EventBuilder.PICK_AFTER_CALENDAR_DAYS))).isTrue();
        assertThat(rejected.settledOn(day61, cal)).isTrue();
    }

    /** Ruling F2: a reappearance at trading day 30 extends the same event (unaffected by the settle
     *  change — the 60-trading-day gap rule for grouping is unchanged). */
    @Test
    void aReappearanceAtTradingDay30ExtendsTheSameEvent() {
        LocalDate d0 = d(2026, 3, 2);
        LocalDate day30 = cal.plus(d0, 30).orElseThrow();
        List<MeasureEvent> events = EventBuilder.candidateEvents(
                List.of(cand("SYNA", d0), cand("SYNA", day30)), List.of(), cal);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventStart()).isEqualTo(d0);
        assertThat(events.getFirst().lastAppearance()).isEqualTo(day30);
    }
}
