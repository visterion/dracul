package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.ExecutorPosition;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Pure money model of the strigoi P&L view (spec 2026-10-06). No I/O: the service hands in the
 * book, the TRIM/TRIM_FILL decision rows, broker prices, a last-close lookup and an EUR
 * converter.
 *
 * <p>Realized money uses exactly the leg model of {@code OutcomeBatchJob.weightedRealizedR}:
 * every TRIM leg {@code (price − leg entry) × qty_closed} — leg entry = the TRIM's own
 * {@code entry_price} (Tech-Sparplan add lots), else the position's {@code entry_price}; a TRIM
 * without a price takes {@code price}/{@code qty} from the TRIM_FILL row with the same
 * {@code order_id} — plus, for CLOSED rows, the final leg {@code (exit_price − entry_price) × qty}.
 * Side-aware like {@code computeR}. Missing data never becomes a 0: the amount is null and the
 * trade carries a flag.
 */
public final class StrigoiPnlCalculator {

    public static final String UNKNOWN = "unknown";
    public static final String FLAG_INCOMPLETE_LEGS = "INCOMPLETE_LEGS";
    public static final String FLAG_NO_PRICE = "NO_PRICE";
    public static final String FLAG_NO_FX = "NO_FX";
    public static final List<String> LEG_ACTIONS = List.of("TRIM", "TRIM_FILL");

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

    /**
     * @param brokerPrices per-unit broker {@code marketPrice} keyed by UPPER-CASED symbol
     * @param lastClose    fallback current price per symbol, null when unknown; called only for
     *                     OPEN trades without a broker price
     * @param toEur        instrument-currency amount → EUR, null when no rate is available
     * @param currency     the instrument currency every amount is quoted in
     */
    public record Inputs(List<ExecutorPosition> positions, List<DecisionLog> legRows,
            Map<String, BigDecimal> brokerPrices, Function<String, BigDecimal> lastClose,
            UnaryOperator<BigDecimal> toEur, String currency, Instant now) {
    }

    /** A priced trade plus what the summary needs but the wire does not carry. */
    public record Priced(String strigoi, PnlTrade trade, BigDecimal realizedNative,
            BigDecimal openCostEur, String sortKey) {
    }

    private StrigoiPnlCalculator() {
    }

    /** {@code source_agent}, or {@link #UNKNOWN} when null/blank. */
    public static String strigoiOf(ExecutorPosition p) {
        String agent = p.sourceAgent();
        return agent == null || agent.isBlank() ? UNKNOWN : agent;
    }

    /** CLOSED, or OPEN with a filled entry. CANCELLED rows (EntryExpiryService cancels only a
     *  never-filled entry) and OPEN rows whose entry has not filled (inserted with the INTENDED
     *  qty) hold no money and are not trades. */
    public static boolean isTrade(ExecutorPosition p) {
        if ("CLOSED".equals(p.status())) return true;
        return "OPEN".equals(p.status()) && p.entryFilledAt() != null;
    }

    public static List<Priced> price(Inputs in) {
        List<Priced> out = new ArrayList<>();
        for (ExecutorPosition p : in.positions()) {
            if (isTrade(p)) out.add(priceOne(p, in));
        }
        return out;
    }

    public static StrigoiPnlSummary summarize(String strigoi, List<Priced> trades) {
        int closed = 0, wins = 0, losses = 0, open = 0, flagged = 0;
        BigDecimal realized = ZERO_MONEY, unrealized = ZERO_MONEY, openCost = ZERO_MONEY;
        BigDecimal sumR = BigDecimal.ZERO;
        for (Priced t : trades) {
            PnlTrade tr = t.trade();
            if (!tr.flags().isEmpty()) flagged++;
            if (tr.realizedEur() != null) realized = realized.add(tr.realizedEur());
            if ("CLOSED".equals(tr.status())) {
                closed++;
                if (t.realizedNative() != null) {
                    int sign = t.realizedNative().signum();
                    if (sign > 0) wins++;
                    else if (sign < 0) losses++;
                }
                if (tr.r() != null) sumR = sumR.add(tr.r());
            } else {
                open++;
                if (tr.unrealizedEur() != null) unrealized = unrealized.add(tr.unrealizedEur());
                if (t.openCostEur() != null) openCost = openCost.add(t.openCostEur());
            }
        }
        BigDecimal hitRate = closed == 0 ? null
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closed), 4, RoundingMode.HALF_UP);
        return new StrigoiPnlSummary(strigoi, closed, wins, losses, hitRate, realized, unrealized,
                realized.add(unrealized), sumR, open, openCost, flagged);
    }

    /** One summary per strigoi, by name ascending, {@link #UNKNOWN} last. */
    public static List<StrigoiPnlSummary> overview(List<Priced> all) {
        Map<String, List<Priced>> byStrigoi = new TreeMap<>(
                Comparator.comparing((String s) -> UNKNOWN.equals(s)).thenComparing(Comparator.naturalOrder()));
        for (Priced t : all) byStrigoi.computeIfAbsent(t.strigoi(), k -> new ArrayList<>()).add(t);
        List<StrigoiPnlSummary> out = new ArrayList<>();
        byStrigoi.forEach((strigoi, trades) -> out.add(summarize(strigoi, trades)));
        return out;
    }

    /** OPEN first (newest entry first), then CLOSED by close date descending. */
    public static List<PnlTrade> sortedTrades(List<Priced> trades) {
        return trades.stream()
                .sorted(Comparator.comparing(Priced::sortKey).reversed())
                .map(Priced::trade)
                .toList();
    }

    private static Priced priceOne(ExecutorPosition p, Inputs in) {
        boolean open = "OPEN".equals(p.status());
        List<String> flags = new ArrayList<>();

        BigDecimal realizedNative = realizedNative(p, in, open);
        if (realizedNative == null) flags.add(FLAG_INCOMPLETE_LEGS);

        BigDecimal unrealizedNative = null;
        BigDecimal openCostNative = null;
        if (open) {
            BigDecimal current = p.symbol() == null ? null : in.brokerPrices().get(upper(p.symbol()));
            if (current == null && p.symbol() != null) current = in.lastClose().apply(p.symbol());
            if (current == null || p.qty() == null || p.entryPrice() == null) {
                flags.add(FLAG_NO_PRICE);
            } else {
                unrealizedNative = signedMove(p, p.entryPrice(), current).multiply(p.qty());
            }
            if (p.qty() != null && p.entryPrice() != null) openCostNative = p.qty().multiply(p.entryPrice());
        }

        BigDecimal realizedEur = eur(realizedNative, in.toEur(), flags);
        BigDecimal unrealizedEur = eur(unrealizedNative, in.toEur(), flags);
        BigDecimal openCostEur = eur(openCostNative, in.toEur(), flags);

        PnlTrade trade = new PnlTrade(p.id(), p.symbol(), p.status(), day(p.entryDate()),
                open ? null : day(p.closedAt()), p.qty(), p.entryPrice(), open ? null : p.exitPrice(),
                in.currency(), realizedEur, unrealizedEur, open ? null : p.realizedR(),
                open ? null : p.exitReason(), List.copyOf(flags));
        String sortKey = open ? "1|" + nz(p.entryDate()) : "0|" + nz(p.closedAt());
        return new Priced(strigoiOf(p), trade, realizedNative, openCostEur, sortKey);
    }

    /** Σ TRIM legs (+ the final leg when CLOSED) in instrument currency; null when any leg lacks
     *  qty/price/entry — never a partial figure (same rule as OutcomeBatchJob). */
    private static BigDecimal realizedNative(ExecutorPosition p, Inputs in, boolean open) {
        Instant from = windowFrom(p);
        Instant trimTo = open ? in.now() : windowTo(p, in.now());
        List<DecisionLog> trims = owned(in.legRows(), p, "TRIM", from, trimTo);
        // A fill can be written by a reconcile pass after the close — its window ends NOW.
        List<DecisionLog> fills = owned(in.legRows(), p, "TRIM_FILL", from, in.now());
        Map<String, JsonNode> fillByOrderId = new HashMap<>();
        for (DecisionLog f : fills) {
            String oid = text(f.orderJson(), "order_id");
            if (oid != null) fillByOrderId.putIfAbsent(oid, f.orderJson());
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (DecisionLog t : trims) {
            JsonNode oj = t.orderJson();
            BigDecimal qtyClosed = decimal(oj, "qty_closed");
            BigDecimal price = decimal(oj, "price");
            BigDecimal legEntry = decimal(oj, "entry_price");
            String orderId = text(oj, "order_id");
            if (price == null && orderId != null && fillByOrderId.containsKey(orderId)) {
                JsonNode fill = fillByOrderId.get(orderId);
                price = decimal(fill, "price");
                BigDecimal fillQty = decimal(fill, "qty");
                if (fillQty != null) qtyClosed = fillQty;
            }
            BigDecimal entry = legEntry != null ? legEntry : p.entryPrice();
            if (qtyClosed == null || price == null || entry == null) return null;
            sum = sum.add(signedMove(p, entry, price).multiply(qtyClosed));
        }
        if (!open) {
            if (p.qty() == null || p.exitPrice() == null || p.entryPrice() == null) return null;
            sum = sum.add(signedMove(p, p.entryPrice(), p.exitPrice()).multiply(p.qty()));
        }
        return sum;
    }

    /** Rows of {@code action} on the position's symbol that belong to it: a row stamped with
     *  {@code order_json.position_id} belongs to that position only; an unstamped (pre-linkage)
     *  row falls back to the symbol + [from, to] window of OutcomeBatchJob. */
    private static List<DecisionLog> owned(List<DecisionLog> rows, ExecutorPosition p, String action,
            Instant from, Instant to) {
        List<DecisionLog> out = new ArrayList<>();
        for (DecisionLog d : rows) {
            if (!action.equals(d.action()) || p.symbol() == null || !p.symbol().equals(d.symbol())) continue;
            JsonNode pid = d.orderJson() == null ? null : d.orderJson().path("position_id");
            boolean linked = pid != null && !pid.isMissingNode() && !pid.isNull();
            if (linked) {
                if (pid.canConvertToLong() && p.id() != null && pid.asLong() == p.id()) out.add(d);
                continue;
            }
            Instant at = parseInstant(d.createdAt());
            if (from != null && at != null && !at.isBefore(from) && !at.isAfter(to)) out.add(d);
        }
        return out;
    }

    /** ±1 calendar day pad, exactly as OutcomeBatchJob (local-rendered dates vs UTC instants). */
    private static Instant windowFrom(ExecutorPosition p) {
        LocalDate entryDay = parseLocalDate(p.entryDate());
        return entryDay == null ? null : entryDay.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant windowTo(ExecutorPosition p, Instant now) {
        LocalDate closedDay = parseLocalDate(p.closedAt());
        return closedDay == null ? now : closedDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal signedMove(ExecutorPosition p, BigDecimal entry, BigDecimal price) {
        return "SELL".equalsIgnoreCase(p.side()) ? entry.subtract(price) : price.subtract(entry);
    }

    private static BigDecimal eur(BigDecimal amount, UnaryOperator<BigDecimal> toEur, List<String> flags) {
        if (amount == null) return null;
        BigDecimal converted = toEur.apply(amount);
        if (converted == null) {
            if (!flags.contains(FLAG_NO_FX)) flags.add(FLAG_NO_FX);
            return null;
        }
        return converted.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String day(String s) {
        return s == null || s.length() < 10 ? s : s.substring(0, 10);
    }

    private static LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code Timestamp.toString()} rendering (what the repositories produce) or ISO-8601. */
    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Timestamp.valueOf(s).toInstant();
        } catch (IllegalArgumentException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
