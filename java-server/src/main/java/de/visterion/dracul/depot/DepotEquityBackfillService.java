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
 *   cash(d)   = cash(anchor) + net cash movement in (d, anchorDay]
 *   equity(d) = cash(d) + sum over symbols of qty(d) * close(d) / fx(d)
 * </pre>
 *
 * <p>At the anchor day this is exact by construction, which is why the seam needs no
 * smoothing and no residual gets distributed. The error grows towards the left edge instead,
 * where fees, dividends and the book-versus-broker price drift accumulate (spec §1.6 bounds
 * it at 2.5–7 %).
 *
 * <p>The whole series is computed and checked in memory first — every day's equity and cash,
 * plus the seam comparison against the anchor — and only THEN written, in a second pass. A
 * run that dies halfway through an Agora timeout leaves no half curve behind: either nothing
 * was written yet, or everything already checked out.
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

    /**
     * {@code daysInserted} and {@code daysCorrected} are kept apart on purpose. For a feature
     * whose whole point is repeatability, "180 written" reading identically for a first run
     * and for a run that silently changed 180 existing numbers is the wrong granularity: the
     * second case means the inputs moved and deserves a look. The split comes from
     * {@link DepotEquitySnapshotRepository.SnapshotWrite#inserted}, i.e. from Postgres's
     * {@code (xmax = 0)}, not from a guess in this class.
     *
     * <p>{@code daysDeletedStale} counts RECONSTRUCTED rows an earlier, wider run left behind
     * (see {@link DepotEquitySnapshotRepository#deleteStaleReconstructedBefore}).
     */
    public record BackfillReport(String connection, String from, String to,
                                 int daysInserted, int daysCorrected, int daysUnchanged,
                                 int daysSkippedUnpriced, int daysDeletedStale,
                                 List<String> missingBars, List<String> excludedPositions,
                                 BigDecimal seamDelta, BigDecimal seamDeltaPct) {
    }

    /** One computed-but-not-yet-written day. Kept in memory until the whole series checks out. */
    private record ReconstructedDay(LocalDate date, Instant asOf, BigDecimal equity, BigDecimal cash) {
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
            // An empty book still needs to clear whatever an earlier, wider run left behind:
            // if every position closed or vanished from the book entirely, there is nothing
            // left to reconstruct a curve from, but the earlier RECONSTRUCTED rows are exactly
            // as stale as if the window had merely shrunk — they must not survive untouched.
            int deleted = repo.deleteStaleReconstructedBefore(connection, GRANULARITY,
                    anchor.asOf(), List.of());
            return new BackfillReport(connection, null, null, 0, 0, 0, 0, deleted,
                    List.of(), List.of(), null, null);
        }

        Map<String, BigDecimal> brokerQty = brokerHoldings(connection);
        PositionLedger.Ledger ledger = PositionLedger.build(book, brokerQty);

        // The left edge is derived from the LEDGER's real holdings, not from the raw book: a
        // CANCELLED position, or one open in the book but absent at the broker, is excluded
        // from the ledger and must not drag the reconstruction window back before the first
        // REAL purchase.
        Optional<LocalDate> firstHoldingDate = ledger.holdings().stream()
                .map(PositionLedger.Holding::from)
                .min(LocalDate::compareTo);
        if (firstHoldingDate.isEmpty()) {
            // Every book position was excluded — there is no real holding to reconstruct a
            // curve from, though the exclusions themselves are still worth surfacing. Same
            // reasoning as the book.isEmpty() branch above: an earlier, wider run's
            // RECONSTRUCTED rows must still be cleared, not left behind pretending they still
            // apply to a book that no longer supports any of them.
            int deleted = repo.deleteStaleReconstructedBefore(connection, GRANULARITY,
                    anchor.asOf(), List.of());
            return new BackfillReport(connection, null, null, 0, 0, 0, 0, deleted,
                    List.of(), ledger.excluded(), null, null);
        }
        // entry_date is NOT NULL in the schema, so firstHoldingDate cannot be empty due to a
        // null entryDate; the .isEmpty() branch above only covers "every position excluded".
        LocalDate firstEntry = firstHoldingDate.get();

        LocalDate to = anchorDay.minusDays(1);

        // Symbols that actually contribute a holding; excluded ones must not cost an Agora call.
        TreeSet<String> symbols = new TreeSet<>();
        for (PositionLedger.Holding h : ledger.holdings()) symbols.add(h.symbol());

        List<String> missingBars = new ArrayList<>();
        Map<String, NavigableMap<LocalDate, BigDecimal>> closes = new java.util.HashMap<>();
        for (String s : symbols) closes.put(s, fetchBars(s));
        NavigableMap<LocalDate, BigDecimal> fx = fetchBars(FX_SYMBOL);

        // Cash events with no FX rate at all: named once here, not once per trading day. A
        // cash event has a fixed date, so if that date has no rate it is true regardless of
        // which day is being reconstructed — checking it inside the per-day loop below would
        // produce one identical entry per trading day instead of once per event.
        for (PositionLedger.CashEvent e : ledger.events()) {
            if (!e.date().isAfter(anchorDay) && at(fx, e.date()) == null) {
                missingBars.add(e.symbol() + "@" + e.date() + " (cash event, no FX rate)");
            }
        }

        // Every day any fetched bar series (prices AND fx) has data for, capped at the anchor.
        TreeSet<LocalDate> allBarDays = new TreeSet<>();
        for (var series : closes.values()) {
            for (LocalDate d : series.keySet()) {
                if (!d.isAfter(anchorDay)) allBarDays.add(d);
            }
        }
        for (LocalDate d : fx.keySet()) {
            if (!d.isAfter(anchorDay)) allBarDays.add(d);
        }

        // One trading day before the first purchase, so the opening capital is visible — and
        // not one day more. Everything earlier would be a flat line asserting the account
        // already held its full capital, written into the same table as real measurements.
        LocalDate from = allBarDays.headSet(firstEntry, false).isEmpty()
                ? firstEntry
                : allBarDays.headSet(firstEntry, false).last();

        TreeSet<LocalDate> tradingDays = new TreeSet<>();
        for (LocalDate d : allBarDays) {
            if (!d.isBefore(from)) tradingDays.add(d);
        }

        // Pass 1: compute and check the whole series in memory. Nothing is written yet.
        List<ReconstructedDay> days = new ArrayList<>();
        int skippedUnpriced = 0;
        BigDecimal seamDelta = null;
        BigDecimal seamDeltaPct = null;

        for (LocalDate d : tradingDays) {
            BigDecimal cash = anchor.cash()
                    .add(netCashAfterInAccountCurrency(ledger, fx, d, anchorDay))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal positions = BigDecimal.ZERO;
            boolean unpriced = false;
            for (var e : ledger.holdingsOn(d).entrySet()) {
                BigDecimal close = at(closes.get(e.getKey()), d);
                BigDecimal rate = at(fx, d);
                if (close == null || rate == null) {
                    missingBars.add(e.getKey() + "@" + d);
                    unpriced = true;
                    continue;
                }
                positions = positions.add(e.getValue().multiply(close)
                        .divide(rate, 6, RoundingMode.HALF_UP));
            }
            BigDecimal equity = cash.add(positions).setScale(SCALE, RoundingMode.HALF_UP);

            if (d.equals(anchorDay)) {
                if (unpriced) {
                    // A partial equity at the anchor is not a signal, it is noise: the seam
                    // check exists to certify the whole run, and a value missing a holding's
                    // price would read as a large fabricated price/FX drift. The gap is
                    // already named in missingBars above; nothing is hidden by leaving this
                    // null instead of wrong.
                    seamDelta = null;
                    seamDeltaPct = null;
                } else {
                    // The self-check: not written, only compared. cash is the anchor's own,
                    // so this measures the price and FX side alone.
                    seamDelta = equity.subtract(anchor.equity()).setScale(SCALE, RoundingMode.HALF_UP);
                    seamDeltaPct = anchor.equity().signum() == 0 ? null
                            : seamDelta.multiply(BigDecimal.valueOf(100))
                                    .divide(anchor.equity(), SCALE, RoundingMode.HALF_UP);
                }
                continue;
            }

            if (unpriced) {
                // A gap is honest; a number missing a holding it could not price is not. The
                // day is named in missingBars above; it is better absent from the chart than
                // present and wrong.
                skippedUnpriced++;
                continue;
            }

            Instant asOf = d.atStartOfDay(ZoneOffset.UTC).toInstant().truncatedTo(ChronoUnit.DAYS);
            days.add(new ReconstructedDay(d, asOf, equity, cash));
        }

        // tradingDays comes exclusively from fetched bar dates, so a weekday on which the
        // market was closed (Thanksgiving, Good Friday, Labor Day) still carries a MEASURED
        // snapshot row — the job runs on weekdays — but no bar. The anchor day then never
        // enters the loop above and the seam check never runs at all, leaving seamDelta at its
        // initial null: the SAME value that means "the anchor had an unpriced holding". Two
        // very different situations must not read identically to the operator.
        //
        // The anchor is deliberately NOT added to tradingDays here. Doing so would seam-check
        // the measured equity against a close carried forward from the previous bar day and
        // produce a reassuring number out of stale data — worse than no number.
        if (!tradingDays.contains(anchorDay)) {
            missingBars.add("anchor@" + anchorDay + " (no bar, seam check not performed)");
            log.warn("equity backfill [{}]: the anchor day {} has no bar of its own, so the "
                            + "seam check was not performed; seamDelta stays null",
                    connection, anchorDay);
        }

        // Pass 2: write. Everything above already checked out, so a failure here (an Agora
        // call does not happen in this loop — only a database write) never leaves a half curve;
        // it leaves whatever prefix of this already-validated list made it to the database.
        // Before writing: drop RECONSTRUCTED rows an EARLIER, WIDER run left behind. The run
        // must stay repeatable after the book improves, and "improves" often means the window
        // SHRINKS — a position turns out never to have been filled, or drops out of the
        // broker's holdings, and firstHoldingDate moves right. Without this, run 1's older
        // rows survive as RECONSTRUCTED, computed from numbers this run considers wrong, and
        // the chart draws them dashed and connected as if they belonged to the same
        // reconstruction. (A day this run skipped as unpriced is stale by the same argument:
        // this run cannot vouch for it, so it must not stay behind pretending it can.)
        int deletedStale = repo.deleteStaleReconstructedBefore(connection, GRANULARITY,
                anchor.asOf(), days.stream().map(ReconstructedDay::asOf).toList());

        int inserted = 0;
        int corrected = 0;
        int unchanged = 0;
        for (ReconstructedDay day : days) {
            Optional<DepotEquitySnapshotRepository.SnapshotWrite> w = repo.upsertReconstructed(
                    connection, day.asOf(), GRANULARITY, day.equity(), day.cash(), currency);
            if (w.isPresent()) {
                if (w.get().inserted()) inserted++; else corrected++;
            } else if (day.date().isBefore(anchorDay)) {
                // Expected: already RECONSTRUCTED with identical values (an ordinary re-run).
                // The anchor is by definition the earliest MEASURED row (firstMeasured, ORDER
                // BY as_of ASC LIMIT 1), so no day strictly before it can already be MEASURED —
                // this branch cannot mean "skipped because measured".
                unchanged++;
            } else {
                // Structurally unreachable today: every entry in `days` is strictly before
                // anchorDay by construction (the anchor day itself is handled separately,
                // above, and never added to `days`). Kept as a loud guard rather than an
                // assumption: if a future change to the trading-day window ever let a day at
                // or after the anchor reach this loop, an empty Optional here means the
                // database refused it because it is (or has become) MEASURED — a backdated
                // measurement or a manual insert racing this run — and that is exactly the
                // drift this backfill must never paper over as a silent "unchanged".
                log.error("equity backfill [{}]: upsertReconstructed refused day {} which is "
                                + "not before the anchor {} — this should be structurally impossible",
                        connection, day.date(), anchorDay);
                unchanged++;
            }
        }

        BackfillReport report = new BackfillReport(connection, from.toString(), to.toString(),
                inserted, corrected, unchanged, skippedUnpriced, deletedStale,
                List.copyOf(missingBars), ledger.excluded(), seamDelta, seamDeltaPct);
        log.info("equity backfill [{}]: {} inserted, {} corrected, {} unchanged, "
                        + "{} skipped-unpriced, {} stale deleted, seamDelta {} ({}%)",
                connection, inserted, corrected, unchanged, skippedUnpriced, deletedStale,
                seamDelta, seamDeltaPct);
        return report;
    }

    /**
     * The broker's holdings on the anchor day. Used only to drop book-open positions the
     * broker never received (spec §3.4).
     *
     * <p>Any currency other than USD aborts the run — including the ACCOUNT currency. Every
     * consumer downstream values a holding as {@code qty * close / fx}, with a single
     * {@code EURUSD=X} series and no per-position currency anywhere in the pipeline: a
     * EUR-denominated position on a EUR account would be divided by the FX rate all the same
     * and land in the curve understated by the full FX factor, with no missingBars entry, no
     * exclusion and no log line. Admitting it here would deliberately open the door to exactly
     * the silent value loss this feature exists to end. Carrying the currency all the way
     * through the ledger and the per-day valuation is the honest alternative and a much larger
     * change; until a non-USD holding actually exists, refusing to run is the smaller and the
     * safer one.
     */
    private Map<String, BigDecimal> brokerHoldings(String connection) {
        Map<String, BigDecimal> out = new java.util.HashMap<>();
        for (DepotPosition p : depotClient.positions(connection).positions()) {
            String ccy = p.currency();
            if (ccy != null && !"USD".equals(ccy)) {
                throw new BackfillConflictException(
                        "position " + p.symbol() + " is denominated in " + ccy
                        + "; this backfill values every holding as close / EURUSD=X and can "
                        + "only price USD positions");
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
                // Same visibility as the unparsable case below: a hole in the fetched series,
                // not a silent one.
                log.warn("equity backfill: bar missing date or close for {}: {}", symbol, b);
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
     * Net cash movement in {@code (d, anchorDay]}, converted into the account currency at each
     * event's own FX rate. {@link PositionLedger.Ledger#events()} carries amounts in the
     * position's native currency by design (see {@link PositionLedger}'s class doc) — summing
     * them unconverted would add USD figures onto a EUR cash balance.
     *
     * <p>The upper bound at {@code anchorDay} matters as much as the FX conversion:
     * {@link BackfillSourceRepository#bookPositions} has no date filter and returns every
     * position of the connection, including ones opened after the anchor. Their cash is
     * already outside {@code anchor.cash()} — the anchor is a snapshot of that day, not of
     * the account's whole future — so including them here would double-count a purchase that
     * has not happened yet as of any day being reconstructed, and would do so at every single
     * day AND at the anchor's own seam check, turning the one guard the design relies on into
     * a false negative.
     *
     * <p>An event whose date has no FX rate at all is skipped silently HERE — it was already
     * recorded once in {@code missingBars} by the caller before this method is ever called,
     * so re-detecting (and re-appending) it inside this per-day loop would produce one
     * duplicate entry per trading day instead of once per event.
     */
    private static BigDecimal netCashAfterInAccountCurrency(PositionLedger.Ledger ledger,
                                                             NavigableMap<LocalDate, BigDecimal> fx,
                                                             LocalDate d, LocalDate anchorDay) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PositionLedger.CashEvent e : ledger.events()) {
            if (!e.date().isAfter(d) || e.date().isAfter(anchorDay)) continue;
            BigDecimal rate = at(fx, e.date());
            if (rate == null) continue;
            sum = sum.add(e.amount().divide(rate, 6, RoundingMode.HALF_UP));
        }
        return sum;
    }
}
