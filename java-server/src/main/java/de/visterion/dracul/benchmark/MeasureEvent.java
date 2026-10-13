package de.visterion.dracul.benchmark;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One measurable event (spec §2.6). {@code eventStart} is its identity (first appearance for
 * CANDIDATE, prey date for SIGNAL — plan ruling R5); {@code refAt} is the reference T the entry
 * is measured from (prey discovered_at for SIGNAL and picked CANDIDATE, first fetched_at for a
 * rejected CANDIDATE). {@code picked} is null for SIGNAL.
 */
public record MeasureEvent(EventKind kind, String agent, String symbol, LocalDate eventStart, Instant refAt,
                           Boolean picked, LocalDate lastAppearance, Offer offer) {

    public LocalDate refDate() {
        return refAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * A rejected candidate event is final only once a late prey can no longer extend or pick it.
     * Plan ruling F2: settle only once the 60-trading-day gap rule can no longer extend the event —
     * both the 5-calendar-day rule AND a 60-EUNL-trading-day gap since the last appearance must
     * have passed, otherwise a reappearance could still extend the same event or a late prey could
     * still flip it to picked.
     */
    public boolean settledOn(LocalDate today, TradingCalendar cal) {
        if (kind == EventKind.SIGNAL || Boolean.TRUE.equals(picked)) return true;
        boolean calendarDaysPassed = today.isAfter(lastAppearance.plusDays(EventBuilder.PICK_AFTER_CALENDAR_DAYS));
        boolean tradingDaysPassed = cal.countBetween(lastAppearance, today) > EventBuilder.GAP_TRADING_DAYS;
        return calendarDaysPassed && tradingDaysPassed;
    }
}
