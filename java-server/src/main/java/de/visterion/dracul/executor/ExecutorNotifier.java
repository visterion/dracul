package de.visterion.dracul.executor;

import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Best-effort Telegram push for executor happy-path actions (entry placed/filled, exit,
 * tranche-2, stop ratchet). Gated by {@code dracul.executor.notify-enabled} (default true).
 * Never throws — a push failure must never affect persistence. Distinct from
 * {@link TelegramNotifier}'s CRITICAL escalation path.
 *
 * <p>Gated on {@code dracul.executor.enabled} like the rest of the executor package: its
 * dependencies ({@link ExecutorPositionRepository}, {@link ExecutorSignalRepository}) are
 * themselves {@code @ConditionalOnProperty}, so an unconditional bean would break every
 * application context that doesn't enable the executor.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorNotifier {

    private static final Logger log = LoggerFactory.getLogger(ExecutorNotifier.class);
    private static final int THESIS_MAX = 200;

    private final TelegramNotifier telegram;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorSignalRepository signalRepo;
    private final boolean enabled;
    private final String currency;

    public ExecutorNotifier(
            TelegramNotifier telegram,
            ExecutorPositionRepository positionRepo,
            ExecutorSignalRepository signalRepo,
            @Value("${dracul.executor.notify-enabled:true}") boolean enabled,
            @Value("${dracul.executor.instrument-currency:USD}") String currency) {
        this.telegram = telegram;
        this.positionRepo = positionRepo;
        this.signalRepo = signalRepo;
        this.enabled = enabled;
        this.currency = currency;
    }

    // ---- action methods (added in later tasks, plus notifyStopRatchet below) ----

    public void notifyStopRatchet(ExecutorPosition position, BigDecimal oldStop,
                                  BigDecimal newStop, String venue) {
        if (!enabled) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🛡️ STOP NACHGEZOGEN ").append(position.symbol())
              .append(" (").append(venue).append(")\n");
            sb.append(num(oldStop)).append(" → ").append(num(newStop));
            appendInvestedTotal(sb);
            telegram.notifyDigest(sb.toString());
        } catch (Exception e) {
            log.warn("executor notify (ratchet) failed for {}: {}", position.symbol(), e.getMessage());
        }
    }

    public void notifyEntryPlaced(ExecutorSignal signal, String side, BigDecimal qty,
                                  BigDecimal price, BigDecimal stop, String venue) {
        if (!enabled) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🟢 ENTRY PLATZIERT ").append(signal.symbol())
              .append(" (").append(venue).append(")\n");
            sb.append(side).append(" ").append(plain(qty)).append(" @ ").append(num(price))
              .append(" — Stop ").append(num(stop));
            appendSignalLine(sb, signal);
            sb.append("\nBetrag: ").append(money(amount(qty, price)));
            appendInvestedTotal(sb);
            telegram.notifyDigest(sb.toString());
        } catch (Exception e) {
            log.warn("executor notify (entry placed) failed for {}: {}", signal.symbol(), e.getMessage());
        }
    }

    public void notifyEntryFilled(ExecutorPosition position, BigDecimal qty,
                                  BigDecimal fillPrice, String venue) {
        if (!enabled) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("✅ ENTRY GEFÜLLT ").append(position.symbol())
              .append(" (").append(venue).append(")\n");
            sb.append(position.side()).append(" ").append(plain(qty)).append(" @ ").append(num(fillPrice))
              .append(" — Stop ").append(num(position.activeStop()));
            appendSignalLineFor(sb, position.sourceSignalId());
            sb.append("\nBetrag: ").append(money(amount(qty, fillPrice)));
            appendInvestedTotal(sb);
            telegram.notifyDigest(sb.toString());
        } catch (Exception e) {
            log.warn("executor notify (entry filled) failed for {}: {}", position.symbol(), e.getMessage());
        }
    }

    public void notifyExit(ExecutorPosition position, String exitReason, BigDecimal exitPrice,
                           BigDecimal realizedR, String venue) {
        if (!enabled) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🔻 EXIT ").append(position.symbol())
              .append(" (").append(venue).append(") — ").append(exitReason).append("\n");
            sb.append(plain(position.qty())).append(" @ ").append(num(exitPrice));
            if (realizedR != null) sb.append(" · realized ").append(plain(realizedR)).append("R");
            appendSignalLineFor(sb, position.sourceSignalId());
            sb.append("\nBetrag: ").append(money(amount(position.qty(), exitPrice)));
            appendInvestedTotal(sb);
            telegram.notifyDigest(sb.toString());
        } catch (Exception e) {
            log.warn("executor notify (exit) failed for {}: {}", position.symbol(), e.getMessage());
        }
    }

    public void notifyTranche2(ExecutorPosition position, BigDecimal addedQty, BigDecimal addPrice,
                               BigDecimal newQty, BigDecimal newAvgEntry, String reason, String venue) {
        if (!enabled) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("➕ TRANCHE 2 ").append(position.symbol())
              .append(" (").append(venue).append(") — ").append(reason).append("\n");
            sb.append("+").append(plain(addedQty)).append(" @ ").append(num(addPrice))
              .append(" → gesamt ").append(plain(newQty)).append(" @ Ø ").append(num(newAvgEntry));
            appendSignalLineFor(sb, position.sourceSignalId());
            sb.append("\nBetrag: ").append(money(amount(addedQty, addPrice)));
            appendInvestedTotal(sb);
            telegram.notifyDigest(sb.toString());
        } catch (Exception e) {
            log.warn("executor notify (tranche2) failed for {}: {}", position.symbol(), e.getMessage());
        }
    }

    // ---- helpers ----

    private void appendSignalLine(StringBuilder sb, ExecutorSignal signal) {
        try {
            sb.append("\n").append(signalLine(signal.mechanism(), signal.confidence(), signal.thesis()));
        } catch (Exception e) {
            log.warn("signal line render failed: {}", e.getMessage());
        }
    }

    private void appendSignalLineFor(StringBuilder sb, String sourceSignalId) {
        String line = resolveSignalLine(sourceSignalId);
        if (!line.isBlank()) sb.append("\n").append(line);
    }

    private BigDecimal amount(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) return null;
        return qty.multiply(price);
    }

    /** Whole/undecorated number (no currency, no forced 2 decimals) for share counts and R, German locale. */
    private String plain(BigDecimal v) {
        if (v == null) return "?";
        return new DecimalFormat("#,##0.##########", new DecimalFormatSymbols(Locale.GERMANY)).format(v);
    }

    /** Appends "\nInvestiert gesamt: X" or nothing if the query fails. Best-effort. */
    private void appendInvestedTotal(StringBuilder sb) {
        BigDecimal total = investedTotal();
        if (total != null) sb.append("\nInvestiert gesamt: ").append(money(total));
    }

    /** Sum of qty*entryPrice over FILLED (entryExpiresAt == null) open positions; null on failure. */
    private BigDecimal investedTotal() {
        try {
            BigDecimal sum = BigDecimal.ZERO;
            for (ExecutorPosition p : positionRepo.findOpen()) {
                if (p.entryExpiresAt() == null && p.qty() != null && p.entryPrice() != null) {
                    sum = sum.add(p.qty().multiply(p.entryPrice()));
                }
            }
            return sum;
        } catch (Exception e) {
            log.warn("investedTotal computation failed: {}", e.getMessage());
            return null;
        }
    }

    /** "Mechanismus: X · Confidence: Y\nThese: …" via signal lookup; "" on any failure/null. */
    private String resolveSignalLine(String sourceSignalId) {
        if (sourceSignalId == null) return "";
        try {
            ExecutorSignal s = signalRepo.findById(sourceSignalId);
            if (s == null) return "";
            return signalLine(s.mechanism(), s.confidence(), s.thesis());
        } catch (Exception e) {
            log.warn("signal line resolution failed for {}: {}", sourceSignalId, e.getMessage());
            return "";
        }
    }

    /** Builds "Mechanismus: X · Confidence: Y\nThese: Z" (Confidence/These lines omitted when absent). */
    private String signalLine(String mechanism, Double confidence, JsonNode thesis) {
        StringBuilder line = new StringBuilder();
        line.append("Mechanismus: ").append(mechanism == null ? "?" : mechanism);
        if (confidence != null) line.append(" · Confidence: ").append(conf(confidence));
        String t = thesisText(thesis);
        if (!t.isBlank()) line.append("\nThese: ").append(t);
        return line.toString();
    }

    private String thesisText(JsonNode thesis) {
        if (thesis == null || thesis.isNull()) return "";
        String t = thesis.isValueNode() ? thesis.asString() : thesis.toString();
        if (t == null) return "";
        t = t.trim();
        return t.length() > THESIS_MAX ? t.substring(0, THESIS_MAX) + "…" : t;
    }

    private String money(BigDecimal v) {
        return num(v) + " " + currency;
    }

    private String num(BigDecimal v) {
        if (v == null) return "?";
        return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.GERMANY)).format(v);
    }

    private String conf(Double v) {
        return new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.GERMANY)).format(v);
    }
}
