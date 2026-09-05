package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Everything an entry decision needs, assembled in one place ({@link EntryContextAssembler}) so
 * that pure decision logic (veto rules, position sizing) never talks to Agora, the broker, or the
 * database directly. Optional fields ({@link #swingLow}, {@link #dayHigh}) may be null without
 * being flagged in {@link #missing}; every other datum absent because of an unavailable upstream
 * is both null/default AND named in {@link #missing}.
 */
public record EntryContext(
        AccountSnapshot account,              // null when unavailable
        BigDecimal price,                     // currentClose, instrument ccy
        BigDecimal atr,
        BigDecimal swingLow,                  // nullable, NOT mandatory
        BigDecimal adv20Notional,             // adv20(volume-SMA) * price, instrument ccy
        BigDecimal dayHigh,                   // last bar's high, nullable, NOT mandatory
        String candidateSector,
        List<ExecutorPosition> openPositions,
        List<Cooldown> activeCooldowns,
        List<ExecutorSignal> pendingSignals,
        int entriesThisWeek,                  // NEW positions (tranche row count) entered this ISO calendar week
        long signalAgeTradingDays,            // -1 when createdAt unparseable
        BigDecimal trancheAmount,             // budget/tranche-count converted to instrument ccy
        BigDecimal totalBudget,               // account ccy (config)
        BigDecimal openExposure,              // sum qty*entryPrice converted to account ccy
        BigDecimal openHeat,                  // sum qty*(entryPrice-activeStop) converted to account ccy
        Map<String, String> openMechanisms,   // open-position symbol -> mechanism, via signalRepo.findById(p.sourceSignalId()); entries with unknown source signal omitted
        BigDecimal fxToAccount,               // multiplier instrument ccy -> account ccy; BigDecimal.ONE on cache miss (FxService identity fallback)
        List<String> missing,                 // names of absent MANDATORY data
        String quoteCurrency,                 // actual instrument currency from get_quote, NULL-preserving (a missing currency stays null, NOT coerced to USD); consumed by the CURRENCY_MISMATCH veto
        /** Short-period ATR (`atr_short`), nullable — a symbol with too few bars has none. */
        BigDecimal atrShort,
        /** {@code max(atr, atrShort)}, or {@code atr} when {@code atrShort} is null. THE ATR every
         *  stop-distance decision uses: the sizer's stop window, the stop clamp, the sizing call
         *  and the broker-stop buffer. The chase/drift/value-anchor vetos and the LLM's own `atr`
         *  field deliberately stay on {@link #atr()}. */
        BigDecimal atrEff,
        /** Account-currency exposure of the open book per mechanism of each position's OWN source
         *  signal (key upper-cased), plus {@code "UNRESOLVED"} for positions whose source signal or
         *  mechanism is missing. Same per-position FX rounding as {@link #openExposure()}, so the two
         *  agree at every cap boundary. Read by the MECHANISM_BUDGET veto (5b). */
        Map<String, BigDecimal> openExposureByMechanism) {
}
