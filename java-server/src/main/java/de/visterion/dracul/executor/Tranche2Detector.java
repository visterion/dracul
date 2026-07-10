package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure decision logic for whether an open tranche-1 position is eligible to add a second
 * tranche. This never touches the broker or the position book — {@link MaintenancePipeline}
 * calls it once per surviving open position after the ratchet stage and surfaces the result on
 * {@link EnrichedPosition} for the Chronicle position book / downstream LLM tools to act on.
 *
 * <p>The hard gate — never eligible below entry (BUY) / above entry (SELL), no averaging down,
 * ever — is checked first and wins over every other condition, including a reinforcing signal.
 * Once past the gate, the first of three conditions to match decides the reason (first match
 * wins, checked in this order):
 *
 * <ol>
 *   <li>{@code R_CONFIRMED} — price has moved at least 1R in the position's favor, where R is
 *       the initial per-share risk ({@code entryPrice - initialStop} for BUY, mirrored for SELL).</li>
 *   <li>{@code NEW_HIGH} — price has extended past the entry-day extreme
 *       ({@link ExecutorPosition#entryDayHigh()}), i.e. day-1 momentum is still running.</li>
 *   <li>{@code REINFORCING_SIGNAL} — a currently pending signal for the same symbol and direction
 *       as the position originates from a mechanism that differs from the mechanism that opened
 *       the position itself, i.e. an independent hunting strategy corroborates the thesis. If the
 *       position's own mechanism is unknown ({@code positionMechanism == null}), this route is
 *       conservatively unavailable — a pending signal can never be judged "different" from an
 *       unknown mechanism, so only {@code R_CONFIRMED}/{@code NEW_HIGH} can fire.</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class Tranche2Detector {

    public static final String R_CONFIRMED = "R_CONFIRMED";
    public static final String NEW_HIGH = "NEW_HIGH";
    public static final String REINFORCING_SIGNAL = "REINFORCING_SIGNAL";

    private static final Tranche2Status NOT_ELIGIBLE = new Tranche2Status(false, null);

    public record Tranche2Status(boolean eligible, String reason) {
    }

    public Tranche2Status detect(ExecutorPosition p, BigDecimal price, List<ExecutorSignal> pendings,
            String positionMechanism) {
        if (p.tranche() != 1 || !"OPEN".equals(p.status()) || price == null) return NOT_ELIGIBLE;

        boolean sell = "SELL".equals(p.side());
        boolean neverBelowEntry = sell
                ? price.compareTo(p.entryPrice()) <= 0
                : price.compareTo(p.entryPrice()) >= 0;
        if (!neverBelowEntry) return NOT_ELIGIBLE;

        if (p.initialStop() != null) {
            BigDecimal rPerShare = sell
                    ? p.initialStop().subtract(p.entryPrice())
                    : p.entryPrice().subtract(p.initialStop());
            if (rPerShare.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal move = sell
                        ? p.entryPrice().subtract(price)
                        : price.subtract(p.entryPrice());
                BigDecimal rMultiple = move.divide(rPerShare, 6, RoundingMode.HALF_UP);
                if (rMultiple.compareTo(BigDecimal.ONE) >= 0) return new Tranche2Status(true, R_CONFIRMED);
            }
        }

        if (p.entryDayHigh() != null) {
            boolean newExtreme = sell
                    ? price.compareTo(p.entryDayHigh()) < 0
                    : price.compareTo(p.entryDayHigh()) > 0;
            if (newExtreme) return new Tranche2Status(true, NEW_HIGH);
        }

        if (pendings != null && positionMechanism != null) {
            for (ExecutorSignal s : pendings) {
                if (s.symbol() == null || !s.symbol().equals(p.symbol())) continue;
                if (s.direction() == null || !s.direction().equals(p.side())) continue;
                if (s.mechanism() == null) continue;
                if (!s.mechanism().equals(positionMechanism)) return new Tranche2Status(true, REINFORCING_SIGNAL);
            }
        }

        return NOT_ELIGIBLE;
    }
}
