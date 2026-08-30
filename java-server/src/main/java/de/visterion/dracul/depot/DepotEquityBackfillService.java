package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reconstructs the equity curve for the days before the first measurement.
 *
 * <p>The pass runs BACKWARDS from the anchor — the oldest measured row:
 *
 * <pre>
 *   cash(d)   = cash(anchor) + net cash movement strictly after d
 *   equity(d) = cash(d) + sum over symbols of qty(d) * close(d) / fx(d)
 * </pre>
 *
 * <p>At the anchor day this is exact by construction, which is why the seam needs no
 * smoothing and no residual gets distributed. The error grows towards the left edge instead,
 * where fees, dividends and the book-versus-broker price drift accumulate (spec §1.6 bounds
 * it at 2.5–7 %).
 *
 * <p>The whole series is built and checked in memory before a single row is written. A run
 * that dies halfway through an Agora timeout leaves no half curve behind.
 */
@Service
public class DepotEquityBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DepotEquityBackfillService.class);

    private static final String GRANULARITY = "DAILY";
    private static final String FX_SYMBOL = "EURUSD=X";
    private static final int OHLC_DAYS = 400;
    private static final int SCALE = 2;

    /** Signals a state the backfill must not paper over. Mapped to 409 by the controller. */
    public static class BackfillConflictException extends RuntimeException {
        public BackfillConflictException(String message) {
            super(message);
        }
    }

    public record BackfillReport(String connection, String from, String to,
                                 int daysWritten, int daysUnchanged, int daysSkippedMeasured,
                                 List<String> missingBars, List<String> excludedPositions,
                                 BigDecimal seamDelta, BigDecimal seamDeltaPct) {
    }

    private final DepotEquitySnapshotRepository repo;
    private final BackfillSourceRepository source;
    private final AgoraClient agora;
    private final AgoraDepotClient depotClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DepotEquityBackfillService(DepotEquitySnapshotRepository repo,
                                      BackfillSourceRepository source,
                                      AgoraClient agora,
                                      AgoraDepotClient depotClient) {
        this.repo = repo;
        this.source = source;
        this.agora = agora;
        this.depotClient = depotClient;
    }

    public BackfillReport run(String connection) {
        DepotEquitySnapshot anchor = repo.firstMeasured(connection, GRANULARITY)
                .orElseThrow(() -> new BackfillConflictException(
                        "no measured DAILY row for " + connection
                        + " — the backfill anchors on one and cannot run before A0 has captured a day"));
        LocalDate anchorDay = anchor.asOf().atZone(ZoneOffset.UTC).toLocalDate();
        String currency = anchor.currency();

        List<BookPosition> book = source.bookPositions(connection);
        for (BookPosition p : book) {
            if (p.enterQty() == null) {
                throw new BackfillConflictException(
                        "no ENTER row in decision_log for " + p.symbol() + " (position " + p.id()
                        + ") — the tranche split would have to be guessed");
            }
        }
        if (book.isEmpty()) {
            return new BackfillReport(connection, null, null, 0, 0, 0,
                    List.of(), List.of(), null, null);
        }

        Map<String, BigDecimal> brokerQty = brokerHoldings(connection, currency);
        PositionLedger.Ledger ledger = PositionLedger.build(book, brokerQty);

        LocalDate to = anchorDay.minusDays(1);

        // Symbols that actually contribute a holding; excluded ones must not cost an Agora call.
        TreeSet<String> symbols = new TreeSet<>();
        for (PositionLedger.Holding h : ledger.holdings()) symbols.add(h.symbol());

        List<String> missingBars = new ArrayList<>();
        Map<String, NavigableMap<LocalDate, BigDecimal>> closes = new java.util.HashMap<>();
        for (String s : symbols) closes.put(s, fetchBars(s));
        NavigableMap<LocalDate, BigDecimal> fx = fetchBars(FX_SYMBOL);

        // Trading days come from the union of every fetched bar series (prices AND fx), not
        // from a book-derived lower bound: a day before the first position's entry still has a
        // cash-only equity value, and a symbol's own bar gap must not hide a day the FX series
        // (or another symbol) does have data for.
        TreeSet<LocalDate> tradingDays = new TreeSet<>();
        for (var series : closes.values()) {
            for (LocalDate d : series.keySet()) {
                if (!d.isAfter(anchorDay)) tradingDays.add(d);
            }
        }
        for (LocalDate d : fx.keySet()) {
            if (!d.isAfter(anchorDay)) tradingDays.add(d);
        }

        LocalDate from = tradingDays.isEmpty() ? null : tradingDays.first();

        int written = 0;
        int unchanged = 0;
        int skippedMeasured = 0;
        BigDecimal seamDelta = null;
        BigDecimal seamDeltaPct = null;

        for (LocalDate d : tradingDays) {
            BigDecimal cash = anchor.cash().add(netCashAfterInAccountCurrency(ledger, fx, d))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal positions = BigDecimal.ZERO;
            for (var e : ledger.holdingsOn(d).entrySet()) {
                BigDecimal close = at(closes.get(e.getKey()), d);
                BigDecimal rate = at(fx, d);
                if (close == null || rate == null) {
                    missingBars.add(e.getKey() + "@" + d);
                    continue;
                }
                positions = positions.add(e.getValue().multiply(close)
                        .divide(rate, 6, RoundingMode.HALF_UP));
            }
            BigDecimal equity = cash.add(positions).setScale(SCALE, RoundingMode.HALF_UP);

            if (d.equals(anchorDay)) {
                // The self-check: not written, only compared. cash is the anchor's own, so
                // this measures the price and FX side alone.
                seamDelta = equity.subtract(anchor.equity()).setScale(SCALE, RoundingMode.HALF_UP);
                seamDeltaPct = anchor.equity().signum() == 0 ? null
                        : seamDelta.multiply(BigDecimal.valueOf(100))
                                .divide(anchor.equity(), SCALE, RoundingMode.HALF_UP);
                continue;
            }
            if (d.isAfter(to)) continue;

            Instant asOf = d.atStartOfDay(ZoneOffset.UTC).toInstant().truncatedTo(ChronoUnit.DAYS);
            Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                    repo.upsertReconstructed(connection, asOf, GRANULARITY, equity, cash, currency);
            if (w.isPresent()) {
                written++;
            } else if (repo.firstMeasured(connection, GRANULARITY).isPresent()
                    && !d.isBefore(anchorDay)) {
                skippedMeasured++;
            } else {
                unchanged++;
            }
        }

        BackfillReport report = new BackfillReport(connection, from == null ? null : from.toString(),
                to.toString(), written, unchanged, skippedMeasured, List.copyOf(missingBars),
                ledger.excluded(), seamDelta, seamDeltaPct);
        log.info("equity backfill [{}]: {} written, {} unchanged, {} skipped, seamDelta {} ({}%)",
                connection, written, unchanged, skippedMeasured, seamDelta, seamDeltaPct);
        return report;
    }

    /**
     * The broker's holdings on the anchor day. Used only to drop book-open positions the
     * broker never received (spec §3.4) — a symbol in a third currency aborts the run,
     * because the matching FX series would be missing and the error invisible.
     */
    private Map<String, BigDecimal> brokerHoldings(String connection, String accountCurrency) {
        Map<String, BigDecimal> out = new java.util.HashMap<>();
        for (DepotPosition p : depotClient.positions(connection).positions()) {
            String ccy = p.currency();
            if (ccy != null && !"USD".equals(ccy) && !ccy.equals(accountCurrency)) {
                throw new BackfillConflictException(
                        "position " + p.symbol() + " is denominated in " + ccy
                        + "; only USD and the account currency " + accountCurrency
                        + " have an FX series here");
            }
            out.put(p.symbol(), p.qty());
        }
        return out;
    }

    private NavigableMap<LocalDate, BigDecimal> fetchBars(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol).put("days", OHLC_DAYS);
        JsonNode res = agora.callTool("get_ohlc", args);
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        for (JsonNode b : res.path("bars")) {
            JsonNode date = b.path("date");
            JsonNode close = b.path("close");
            if (date.isMissingNode() || date.isNull() || close.isMissingNode() || close.isNull()) {
                continue;
            }
            try {
                out.put(LocalDate.parse(date.asString()), new BigDecimal(close.asString()));
            } catch (RuntimeException e) {
                // A single unparsable bar is a hole, not a reason to lose the other 259.
                log.warn("equity backfill: unparsable bar for {}: {}", symbol, b);
            }
        }
        return out;
    }

    /** The close on {@code d}, or the last one before it. Covers holidays and halted symbols. */
    private static BigDecimal at(NavigableMap<LocalDate, BigDecimal> series, LocalDate d) {
        if (series == null) return null;
        var e = series.floorEntry(d);
        return e == null ? null : e.getValue();
    }

    /**
     * Net cash movement strictly after {@code d}, converted into the account currency at each
     * event's own FX rate. {@link PositionLedger.Ledger#events()} carries amounts in the
     * position's native currency by design (see {@link PositionLedger}'s class doc) — summing
     * them unconverted would add USD figures onto a EUR cash balance. An event whose date has
     * no FX rate at all (not even a carried-forward one) is dropped rather than guessed; that
     * mirrors {@link #at} treating an uncovered day as absent.
     */
    private static BigDecimal netCashAfterInAccountCurrency(PositionLedger.Ledger ledger,
                                                             NavigableMap<LocalDate, BigDecimal> fx,
                                                             LocalDate d) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PositionLedger.CashEvent e : ledger.events()) {
            if (!e.date().isAfter(d)) continue;
            BigDecimal rate = at(fx, e.date());
            if (rate == null) continue;
            sum = sum.add(e.amount().divide(rate, 6, RoundingMode.HALF_UP));
        }
        return sum;
    }
}
