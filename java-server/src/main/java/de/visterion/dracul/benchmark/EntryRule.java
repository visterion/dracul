package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/** Spec §2.7: entry = open of the instrument's first trading day whose open is after T. Before
 *  07:00 UTC T's own date counts (some fetches run at 05:00 UTC); otherwise the next day. The
 *  instrument's own bars decide which days are trading days. */
public final class EntryRule {

    public static final LocalTime SAME_DAY_CUTOFF_UTC = LocalTime.of(7, 0);

    private EntryRule() {
    }

    public static LocalDate firstEligibleDate(Instant t) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        return z.toLocalTime().isBefore(SAME_DAY_CUTOFF_UTC) ? z.toLocalDate() : z.toLocalDate().plusDays(1);
    }

    /** Index of the entry bar in {@code bars} (ascending), or -1 when there is none yet. */
    public static int entryIndex(List<OhlcBar> bars, Instant t) {
        LocalDate eligible = firstEligibleDate(t);
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).date().isBefore(eligible)) return i;
        }
        return -1;
    }
}
