package de.visterion.dracul.outcome;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reconstructs the two V49 anchor inputs ({@code reference_bar_date}, {@code reference_atr}) a
 * signal would have carried had Agora's {@code get_indicators} answered at emission time.
 *
 * <p>Mirrors Agora's completed-bar rule ({@code research/ExchangeSessions}): the newest bar is
 * partial while the venue is in session up to close + 20 minutes, so an emission before that
 * anchors on the previous trading day. Only the two venues present in prod are supported (no
 * suffix = New York, {@code .HK} = Hong Kong); anything else fails permanently rather than
 * borrowing a guessed clock. ATR is Agora's {@code atr}: the simple mean of the True Range over
 * the 22 bars ending at the anchor, emitted at scale 4.
 *
 * <p>Pure and stateless; the caller owns fetching and persistence.
 */
public final class AnchorReconstructor {

    public static final String UNSUPPORTED_VENUE = "unsupported venue";
    public static final String NO_DATA = "no OHLC data";
    public static final String NO_BAR_BEFORE_EMISSION = "no bar before emission";
    public static final String STALE = "stale history";
    public static final String TOO_FEW_BARS = "fewer than 23 bars";
    public static final String MALFORMED = "malformed bar in ATR window";

    static final int ATR_PERIOD = 22;
    static final int MAX_STALENESS_DAYS = 7;
    private static final LocalTime CLOSE_PLUS_MARGIN = LocalTime.of(16, 20);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    public sealed interface Result permits Anchor, Failure {}

    public record Anchor(LocalDate barDate, BigDecimal atr) implements Result {}

    public record Failure(String reason, boolean permanent) implements Result {}

    /**
     * @param windowStart the calendar date our fetch asked history back to; a series whose first
     *        bar is later than this genuinely has no older data, so "too little data" is permanent
     */
    public Result reconstruct(String symbol, Instant emittedAt, List<OhlcBar> bars, LocalDate windowStart) {
        ZoneId zone = venueZone(symbol);
        if (zone == null) return new Failure(UNSUPPORTED_VENUE, true);
        List<OhlcBar> series = normalise(bars);
        if (series.isEmpty()) return new Failure(NO_DATA, false);

        boolean historyGenuinelyShort = series.getFirst().date().isAfter(windowStart);
        LocalDate cutoff = cutoff(emittedAt, zone);
        int anchorIdx = -1;
        for (int i = series.size() - 1; i >= 0; i--) {
            if (!series.get(i).date().isAfter(cutoff)) { anchorIdx = i; break; }
        }
        if (anchorIdx < 0) return new Failure(NO_BAR_BEFORE_EMISSION, historyGenuinelyShort);

        OhlcBar anchor = series.get(anchorIdx);
        if (ChronoUnit.DAYS.between(anchor.date(), cutoff) > MAX_STALENESS_DAYS) {
            return new Failure(STALE, true);
        }
        if (anchorIdx < ATR_PERIOD) return new Failure(TOO_FEW_BARS, historyGenuinelyShort);

        for (int i = anchorIdx - ATR_PERIOD; i <= anchorIdx; i++) {
            OhlcBar b = series.get(i);
            if (!positive(b.high()) || !positive(b.low()) || !positive(b.close())) {
                return new Failure(MALFORMED, true);
            }
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = anchorIdx - ATR_PERIOD + 1; i <= anchorIdx; i++) {
            sum = sum.add(trueRange(series.get(i), series.get(i - 1).close()));
        }
        return new Anchor(anchor.date(),
                sum.divide(BigDecimal.valueOf(ATR_PERIOD), 4, RoundingMode.HALF_UP));
    }

    /** Venue-local date of the newest bar that was complete at {@code emittedAt}. */
    static LocalDate cutoff(Instant emittedAt, ZoneId zone) {
        ZonedDateTime local = emittedAt.atZone(zone);
        DayOfWeek dow = local.getDayOfWeek();
        boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        if (weekday && !local.toLocalTime().isBefore(CLOSE_PLUS_MARGIN)) return local.toLocalDate();
        return local.toLocalDate().minusDays(1);
    }

    /** New York for a plain ticker, Hong Kong for {@code .HK}; null for anything else. */
    static ZoneId venueZone(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim();
        if (s.isEmpty() || ISIN.matcher(s).matches()) return null;
        int dot = s.lastIndexOf('.');
        if (dot < 0) return NEW_YORK;
        if (dot > 0 && s.substring(dot + 1).equalsIgnoreCase("HK")) return HONG_KONG;
        return null;
    }

    /** Sorted by date, one row per date, the LAST row for a date wins (Agora's dedupAndSort). */
    private static List<OhlcBar> normalise(List<OhlcBar> bars) {
        Map<LocalDate, OhlcBar> byDate = new LinkedHashMap<>();
        for (OhlcBar b : bars) {
            if (b == null || b.date() == null) continue;
            byDate.remove(b.date());
            byDate.put(b.date(), b);
        }
        List<OhlcBar> out = new ArrayList<>(byDate.values());
        out.sort(Comparator.comparing(OhlcBar::date));
        return out;
    }

    private static BigDecimal trueRange(OhlcBar b, BigDecimal prevClose) {
        BigDecimal hl = b.high().subtract(b.low());
        BigDecimal hc = b.high().subtract(prevClose).abs();
        BigDecimal lc = b.low().subtract(prevClose).abs();
        return hl.max(hc).max(lc);
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
