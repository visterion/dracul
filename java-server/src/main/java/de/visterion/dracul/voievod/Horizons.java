package de.visterion.dracul.voievod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses prey horizon strings ("1d", "3m", "12m", "1y") and decides if a prey is still open. */
public final class Horizons {

    private static final Logger log = LoggerFactory.getLogger(Horizons.class);
    private static final Pattern HORIZON = Pattern.compile("\\s*(\\d+)\\s*([dwmyDWMY])\\s*");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private Horizons() {}

    public static Period parse(String horizon) {
        if (horizon == null) throw new IllegalArgumentException("null horizon");
        Matcher m = HORIZON.matcher(horizon);
        if (!m.matches()) throw new IllegalArgumentException("unparseable horizon: " + horizon);
        int n = Integer.parseInt(m.group(1));
        return switch (Character.toLowerCase(m.group(2).charAt(0))) {
            case 'd' -> Period.ofDays(n);
            case 'w' -> Period.ofWeeks(n);
            case 'm' -> Period.ofMonths(n);
            case 'y' -> Period.ofYears(n);
            default -> throw new IllegalArgumentException("unparseable horizon: " + horizon);
        };
    }

    public static boolean isOpen(String discoveredAt, String horizon, LocalDate today) {
        LocalDate start;
        try {
            start = dateOf(discoveredAt);
        } catch (RuntimeException e) {
            log.warn("voievod: unparseable discoveredAt '{}' — treating prey as open", discoveredAt);
            return true;
        }
        Period p;
        try {
            p = parse(horizon);
        } catch (RuntimeException e) {
            log.warn("voievod: unparseable horizon '{}' — treating prey as open", horizon);
            return true;
        }
        LocalDate expiry = start.plus(p);
        return !expiry.isBefore(today);
    }

    public static LocalDate dateOf(String timestamp) {
        if (timestamp == null) throw new IllegalArgumentException("null timestamp");
        Matcher m = DATE.matcher(timestamp);
        if (!m.find()) throw new IllegalArgumentException("no date in: " + timestamp);
        return LocalDate.parse(m.group(1));
    }

    public static int approxDays(String horizon) {
        try {
            Period p = parse(horizon);
            return p.getYears() * 365 + p.getMonths() * 30 + p.getDays();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static String longest(List<String> horizons) {
        return horizons.stream().max((a, b) -> Integer.compare(approxDays(a), approxDays(b))).orElse("3m");
    }
}
