package de.visterion.dracul.depot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The depot performance curve, read exclusively from {@code depot_equity_snapshot}.
 *
 * <p>Replaces {@code DepotChartService.depotCurve}, which valued TODAY's holdings at historical
 * closes plus TODAY's cash. That construction could never show a position that had been sold:
 * losers leave the holdings list and vanish from the whole history, so the curve could only rise.
 *
 * <p>A day with no DAILY row stays a GAP -- it is never filled from an INTRADAY row of the same
 * day, and never interpolated. A gap is honest; an invented point is not.
 */
@Service
public class DepotEquityCurveService {

    /** {@code t} is {@code YYYY-MM-DD} for DAILY, an ISO-8601 instant for INTRADAY. */
    public record CurvePoint(String t, BigDecimal value, String source) {
    }

    public record RelativePoint(String t, BigDecimal pct) {
    }

    public record EquityCurve(String granularity, List<CurvePoint> points,
                              List<RelativePoint> relative, String currency) {
    }

    private static final Map<String, Integer> RANGE_DAYS =
            Map.of("1w", 7, "1m", 31, "1y", 365);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 2;

    private final DepotEquitySnapshotRepository repo;
    private final Clock clock;

    @Autowired
    public DepotEquityCurveService(DepotEquitySnapshotRepository repo) {
        this(repo, Clock.systemUTC());
    }

    /** Package-private overload with an injectable {@link Clock} for tests. */
    DepotEquityCurveService(DepotEquitySnapshotRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public EquityCurve curve(String connection, String range) {
        String granularity = granularityFor(range);
        Instant from = windowStart(range);

        List<DepotEquitySnapshot> rows = repo.series(connection, granularity, from);

        List<CurvePoint> points = new ArrayList<>(rows.size());
        for (DepotEquitySnapshot r : rows) {
            points.add(new CurvePoint(format(r.asOf(), granularity), r.equity(), r.source()));
        }

        String currency = rows.isEmpty() ? null : rows.getLast().currency();
        return new EquityCurve(granularity, points, relative(points), currency);
    }

    private String granularityFor(String range) {
        if ("1d".equals(range)) return "INTRADAY";
        if ("max".equals(range) || RANGE_DAYS.containsKey(range)) return "DAILY";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid range: " + range);
    }

    /**
     * The window start is a CALENDAR DAY, not an instant. DAILY rows carry as_of = 00:00:00Z, so
     * a {@code now() - 7 days} taken at 10:00Z would exclude the row of day -7 and "1W" would
     * quietly show six days.
     */
    private Instant windowStart(String range) {
        if ("max".equals(range)) return Instant.EPOCH;
        Instant today = Instant.now(clock).truncatedTo(ChronoUnit.DAYS);
        if ("1d".equals(range)) return today;
        return today.minus(RANGE_DAYS.get(range), ChronoUnit.DAYS);
    }

    private String format(Instant asOf, String granularity) {
        return "DAILY".equals(granularity)
                ? DateTimeFormatter.ISO_LOCAL_DATE.format(asOf.atZone(ZoneOffset.UTC))
                : DateTimeFormatter.ISO_INSTANT.format(asOf);
    }

    /** Null below two points: a single point has nothing to be relative to. */
    private List<RelativePoint> relative(List<CurvePoint> points) {
        if (points.size() < 2) return null;
        BigDecimal first = points.getFirst().value();
        List<RelativePoint> out = new ArrayList<>(points.size());
        for (CurvePoint p : points) {
            BigDecimal pct = first.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
                    : p.value().divide(first, 10, RoundingMode.HALF_UP)
                            .subtract(BigDecimal.ONE)
                            .multiply(HUNDRED)
                            .setScale(SCALE, RoundingMode.HALF_UP);
            out.add(new RelativePoint(p.t(), pct));
        }
        return out;
    }
}
