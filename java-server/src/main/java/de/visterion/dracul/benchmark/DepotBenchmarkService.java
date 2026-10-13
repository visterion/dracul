package de.visterion.dracul.benchmark;

import de.visterion.dracul.depot.DepotEquityCurveService;
import de.visterion.dracul.depot.DepotService;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.pnl.StrigoiPnlCalculator;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Depot block of the benchmark summary (spec §4.2, plan ruling R12). TWR = the existing
 * chain-linked {@link DepotEquityCurveService} curve (external_flow removed), EUNL over the same
 * first/last day. Per source Strigoi: {@link DepotBenchmarkCalculator} over the executor book.
 * Same live-visibility gate as the Depots page; cached 15 minutes because it calls Agora.
 */
@Service
public class DepotBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(DepotBenchmarkService.class);
    static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private record Cached(Instant at, DepotBenchmark block) {}

    private final DepotEquityCurveService curves;
    private final ObjectProvider<ExecutorPositionRepository> positions;
    private final BenchmarkBars bars;
    private final DepotService depots;
    private final String connection;
    private final Clock clock;
    private volatile Cached cached;

    @Autowired
    public DepotBenchmarkService(DepotEquityCurveService curves, ObjectProvider<ExecutorPositionRepository> positions,
                                 BenchmarkBars bars, DepotService depots,
                                 @Value("${dracul.executor.connection:depot-1}") String connection) {
        this(curves, positions, bars, depots, connection, Clock.systemUTC());
    }

    DepotBenchmarkService(DepotEquityCurveService curves, ObjectProvider<ExecutorPositionRepository> positions,
                          BenchmarkBars bars, DepotService depots, String connection, Clock clock) {
        this.curves = curves;
        this.positions = positions;
        this.bars = bars;
        this.depots = depots;
        this.connection = connection;
        this.clock = clock;
    }

    /** Null when the connection is not visible to {@code userEmail} or nothing can be computed. */
    public DepotBenchmark block(String userEmail) {
        try {
            if (!depots.isVisible(connection, userEmail)) return null;
        } catch (RuntimeException e) {
            log.warn("benchmark depot block: visibility check for {} failed: {}", connection, e.toString());
            return null;
        }
        Instant now = clock.instant();
        Cached c = cached;
        if (c != null && now.isBefore(c.at().plus(CACHE_TTL))) return c.block();
        DepotBenchmark fresh;
        try {
            fresh = compute(LocalDate.ofInstant(now, ZoneOffset.UTC));
        } catch (RuntimeException e) {
            log.warn("benchmark depot block for {} failed: {}", connection, e.toString());
            return null;
        }
        cached = new Cached(now, fresh);
        return fresh;
    }

    DepotBenchmark compute(LocalDate today) {
        DepotEquityCurveService.EquityCurve curve = curves.curve(connection, "max");
        List<DepotBenchmarkCalculator.PositionInput> inputs = positionInputs();
        if (curve.points().isEmpty() && inputs.isEmpty()) return null;

        LocalDate oldest = today;
        if (!curve.points().isEmpty()) {
            LocalDate first = LocalDate.parse(curve.points().getFirst().t());
            if (first.isBefore(oldest)) oldest = first;
        }
        for (DepotBenchmarkCalculator.PositionInput p : inputs) {
            if (p.entryDate() != null && p.entryDate().isBefore(oldest)) oldest = p.entryDate();
        }
        int lookback = BenchmarkBars.lookbackBars(oldest.minusDays(10), today);
        PriceSeries eunl = bars.fetch(BenchmarkBars.BENCHMARK_SYMBOL, lookback);

        String from = null;
        String to = null;
        BigDecimal twr = null;
        BigDecimal bench = null;
        BigDecimal excess = null;
        if (curve.relative() != null && !curve.relative().isEmpty()) {
            from = curve.points().getFirst().t();
            to = curve.points().getLast().t();
            twr = curve.relative().getLast().pct().movePointLeft(2).setScale(6, RoundingMode.HALF_UP);
            Optional<OhlcBar> start = eunl.lastOnOrBefore(LocalDate.parse(from));
            Optional<OhlcBar> end = eunl.lastOnOrBefore(LocalDate.parse(to));
            if (start.isPresent() && end.isPresent()) {
                bench = end.get().close().divide(start.get().close(), MathContext.DECIMAL64)
                        .subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
                excess = twr.subtract(bench);
            }
        }

        Map<String, PriceSeries> openBars = new HashMap<>();
        Map<String, PriceSeries> fx = new HashMap<>();
        for (DepotBenchmarkCalculator.PositionInput p : inputs) {
            if (p.open() && p.symbol() != null) openBars.computeIfAbsent(p.symbol(), s -> fetchOrEmpty(s, lookback));
            CurrencyResolver.currencyOf(p.symbol())
                    .filter(cur -> !CurrencyResolver.EUR.equals(cur))
                    .ifPresent(cur -> fx.computeIfAbsent(cur, k -> fetchOrEmpty(CurrencyResolver.fxPair(k), lookback)));
        }
        List<StrigoiDepotBenchmark> byStrigoi = DepotBenchmarkCalculator.byStrigoi(inputs,
                s -> openBars.getOrDefault(s, PriceSeries.empty()), fx::get, eunl);
        return new DepotBenchmark(connection, from, to, twr, bench, excess, byStrigoi);
    }

    private List<DepotBenchmarkCalculator.PositionInput> positionInputs() {
        ExecutorPositionRepository repo = positions.getIfAvailable();
        if (repo == null) return List.of();
        return repo.findBookForPnl(connection).stream()
                .filter(StrigoiPnlCalculator::isTrade)
                .map(DepotBenchmarkService::toInput)
                .toList();
    }

    static DepotBenchmarkCalculator.PositionInput toInput(ExecutorPosition p) {
        boolean closed = "CLOSED".equals(p.status());
        return new DepotBenchmarkCalculator.PositionInput(StrigoiPnlCalculator.strigoiOf(p), p.symbol(), p.side(),
                p.qty(), p.entryPrice(), day(p.entryDate()), closed ? p.exitPrice() : null,
                closed ? day(p.closedAt()) : null);
    }

    private PriceSeries fetchOrEmpty(String symbol, int lookback) {
        try {
            return bars.fetch(symbol, lookback);
        } catch (MarketDataException e) {
            log.warn("benchmark depot block: bars for {} unavailable: {}", symbol, e.getMessage());
            return PriceSeries.empty();
        }
    }

    private static LocalDate day(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
