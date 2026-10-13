package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/** Spec §2.7: entry = open of the instrument's first trading day whose open is after T. The
 *  same-day cutoff is the instrument's market open in UTC, per currency: USD and EUR 07:00 (some
 *  fetches run at 05:00 UTC), HKD 01:30 (Hong Kong opens 09:30 HKT = 01:30 UTC). T strictly
 *  before the cutoff counts T's own date; otherwise the next day. Any other currency uses the
 *  07:00 default. The instrument's own bars decide which days are trading days. */
public final class EntryRule {

    public static final LocalTime DEFAULT_CUTOFF_UTC = LocalTime.of(7, 0);
    public static final LocalTime HKD_CUTOFF_UTC = LocalTime.of(1, 30);

    private EntryRule() {
    }

    /** Same-day cutoff in UTC for an instrument quoted in {@code currency}. */
    public static LocalTime cutoffUtc(String currency) {
        return "HKD".equals(currency) ? HKD_CUTOFF_UTC : DEFAULT_CUTOFF_UTC;
    }

    public static LocalDate firstEligibleDate(Instant t, String currency) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        return z.toLocalTime().isBefore(cutoffUtc(currency)) ? z.toLocalDate() : z.toLocalDate().plusDays(1);
    }

    /** Index of the entry bar in {@code bars} (ascending), or -1 when there is none yet. */
    public static int entryIndex(List<OhlcBar> bars, Instant t, String currency) {
        LocalDate eligible = firstEligibleDate(t, currency);
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).date().isBefore(eligible)) return i;
        }
        return -1;
    }
}
