package de.visterion.dracul.executor;

import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Component
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

    // ---- helpers ----

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
            StringBuilder sb = new StringBuilder();
            sb.append("Mechanismus: ").append(s.mechanism() == null ? "?" : s.mechanism());
            if (s.confidence() != null) {
                sb.append(" · Confidence: ").append(conf(s.confidence()));
            }
            String thesis = thesisText(s.thesis());
            if (!thesis.isBlank()) sb.append("\nThese: ").append(thesis);
            return sb.toString();
        } catch (Exception e) {
            log.warn("signal line resolution failed for {}: {}", sourceSignalId, e.getMessage());
            return "";
        }
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
