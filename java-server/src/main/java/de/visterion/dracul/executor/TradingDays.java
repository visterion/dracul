package de.visterion.dracul.executor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * The single trading-day counter in the executor. Extracted from
 * {@code EntryContextAssembler.tradingDayAge} so veto #3 ({@code SIGNAL_EXPIRED}) and
 * {@link PendingSignalSweeper} can never disagree about how old a signal is.
 *
 * <p>Known and deliberately preserved coupling: {@code ExecutorSignal.createdAt} is
 * {@code Timestamp.toString()} of a {@code TIMESTAMPTZ} column and is therefore rendered in the
 * JVM default zone, while {@code LocalDate.now(clock)} is UTC. This method takes a string and
 * cannot see the zone, so the hazard is removed at the runtime instead: the Docker image pins
 * {@code -Duser.timezone=UTC}.
 */
final class TradingDays {

    private TradingDays() {
    }

    /** Trading days (Mon–Fri, no holiday calendar) strictly after the createdAt date up to today
     *  in the given clock's zone; -1 when createdAt is blank or unparseable. */
    static long ageOf(String createdAt, Clock clock) {
        LocalDate entry;
        try {
            if (createdAt == null || createdAt.isBlank()) throw new IllegalArgumentException("blank");
            entry = LocalDate.parse(createdAt.length() > 10 ? createdAt.substring(0, 10) : createdAt);
        } catch (RuntimeException e) {
            return -1L;
        }
        LocalDate today = LocalDate.now(clock);
        long days = 0;
        for (LocalDate d = entry.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) days++;
        }
        return days;
    }
}
