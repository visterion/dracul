package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.OhlcBar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/** Assembles ExitIndicators from Agora TA (get_indicators via AgoraResearch) + gropar domain logic
 *  (gainLossPct vs entry, horizon/TIME_STOP, firedRules). Gropar's sole exit-indicator assembler. */
@Component
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
public class GroparExitIndicators {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GAIN_SCALE = 4;

    private final AgoraResearch research;
    private final int atrPeriod, maFast, maSlow, minBars52w;
    private final BigDecimal atrMultiple;

    @Autowired
    public GroparExitIndicators(AgoraResearch research,
                                @Value("${dracul.gropar.atr-period:22}") int atrPeriod,
                                @Value("${dracul.gropar.atr-multiple:3.0}") String atrMultiple,
                                @Value("${dracul.gropar.ma-fast:50}") int maFast,
                                @Value("${dracul.gropar.ma-slow:200}") int maSlow) {
        this(research, atrPeriod, new BigDecimal(atrMultiple), maFast, maSlow, 250);
    }

    // Test ctor
    GroparExitIndicators(AgoraResearch research, int atrPeriod, BigDecimal atrMultiple,
                         int maFast, int maSlow, int minBars52w) {
        this.research = research;
        this.atrPeriod = atrPeriod; this.atrMultiple = atrMultiple;
        this.maFast = maFast; this.maSlow = maSlow; this.minBars52w = minBars52w;
    }

    public ExitIndicators compute(String symbol, List<OhlcBar> bars,
                                  BigDecimal entryPrice, String verdictCreatedAt, String horizon) {
        if (bars == null || bars.isEmpty()) {
            return new ExitIndicators(null, null, null, false, null, false, null, false,
                    null, false, "NEUTRAL", null, null, false, null, false, List.of());
        }
        OhlcBar last = bars.get(bars.size() - 1);
        BigDecimal currentClose = last.close();

        ExitTa ta = research.exitTa(symbol, atrPeriod, atrMultiple, maFast, maSlow, minBars52w);

        BigDecimal gainLossPct = null;
        if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) != 0) {
            gainLossPct = currentClose.subtract(entryPrice).divide(entryPrice, MC)
                    .multiply(BigDecimal.valueOf(100)).setScale(GAIN_SCALE, RoundingMode.HALF_UP);
        }

        boolean horizonElapsed = computeHorizonElapsed(verdictCreatedAt, horizon, last.date());

        List<String> firedRules = new ArrayList<>();
        if (ta.chandelierBreached()) firedRules.add(ExitRules.CHANDELIER_STOP);
        if (ExitRules.DEATH_CROSS.equals(ta.maCrossState())) firedRules.add(ExitRules.DEATH_CROSS);
        if (horizonElapsed) firedRules.add(ExitRules.TIME_STOP);

        return new ExitIndicators(
                currentClose, gainLossPct,
                ta.atr(), ta.atrAvailable(),
                ta.chandelierStop(), ta.chandelierBreached(),
                ta.maFast(), ta.maFastAvailable(),      // -> ma50
                ta.maSlow(), ta.maSlowAvailable(),      // -> ma200
                ta.maCrossState(),
                ta.high52w(), ta.low52w(), ta.window52wAvailable(),
                null,                                   // daysHeld (v1)
                horizonElapsed,
                List.copyOf(firedRules));
    }

    // --- horizon helpers (gropar domain logic) ---
    private static boolean computeHorizonElapsed(String verdictCreatedAt, String horizon, LocalDate lastBarDate) {
        if (verdictCreatedAt == null || horizon == null || lastBarDate == null) return false;
        try {
            String dateStr = verdictCreatedAt.length() >= 10 ? verdictCreatedAt.substring(0, 10) : verdictCreatedAt;
            LocalDate createdDate = LocalDate.parse(dateStr);
            Period period = parseHorizon(horizon);
            if (period == null) return false;
            return !lastBarDate.isBefore(createdDate.plus(period));
        } catch (Exception e) { return false; }
    }

    private static Period parseHorizon(String horizon) {
        if (horizon == null || horizon.isBlank()) return null;
        String s = horizon.trim().toLowerCase();
        try {
            if (s.endsWith("m")) return Period.ofMonths(Integer.parseInt(s.substring(0, s.length() - 1)));
            if (s.endsWith("y")) return Period.ofYears(Integer.parseInt(s.substring(0, s.length() - 1)));
        } catch (NumberFormatException ignored) { }
        return null;
    }
}
