package de.visterion.dracul.position;

import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.settings.AppSettingsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FX-converted portfolio weights over the depot's open positions (T2.2, spec A1). Base
 * currency is the DISPLAY currency (round 3, F1) — the ratio is base-invariant whenever all
 * conversions succeed and the display currency is what {@link FxService} is warmed toward.
 * hasRate discipline (round 3, F2): {@code FxService.convert} silently returns the amount
 * UNCONVERTED on a cache miss, so every position's rate availability is checked FIRST; any
 * missing rate/marketValue/currency or a blank display currency yields an EMPTY map — weight
 * null on ALL positions, no fake precision. Direction/gain-loss are unaffected.
 */
@Component
public class PortfolioWeights {

    private final FxService fx;
    private final AppSettingsRepository settings;

    public PortfolioWeights(FxService fx, AppSettingsRepository settings) {
        this.fx = fx;
        this.settings = settings;
    }

    /** Weight per symbol (multi-lot lots summed) in percent of total |marketValue|, scale 1
     *  HALF_UP; empty map when any input needed for an honest ratio is missing. */
    public Map<String, BigDecimal> weightsBySymbol(List<HeldPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        String display = settings.getDisplayCurrency();
        if (display == null || display.isBlank()) return Map.of();
        for (HeldPosition p : positions) {
            if (p.marketValue() == null || p.currency() == null
                    || !fx.hasRate(p.currency(), display)) {
                return Map.of();
            }
        }
        Map<String, BigDecimal> absBySymbol = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (HeldPosition p : positions) {
            BigDecimal abs = fx.convert(p.marketValue(), p.currency(), display).abs();
            absBySymbol.merge(p.symbol(), abs, BigDecimal::add);
            total = total.add(abs);
        }
        if (total.signum() == 0) return Map.of();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (var e : absBySymbol.entrySet()) {
            out.put(e.getKey(), e.getValue()
                    .divide(total, MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP));
        }
        return out;
    }

    /** Multi-lot collapse (rounds 2+3): one entry per symbol, quantity = Σ lots (signed),
     *  marketValue = Σ lots (signed), avgPrice = |quantity|-weighted average (null when any
     *  lot lacks avgPrice/quantity), remaining components from the FIRST lot. */
    public static List<HeldPosition> collapseBySymbol(List<HeldPosition> positions) {
        Map<String, List<HeldPosition>> bySymbol = new LinkedHashMap<>();
        for (HeldPosition p : positions) {
            bySymbol.computeIfAbsent(p.symbol(), k -> new java.util.ArrayList<>()).add(p);
        }
        List<HeldPosition> out = new java.util.ArrayList<>();
        for (List<HeldPosition> lots : bySymbol.values()) {
            if (lots.size() == 1) {
                out.add(lots.get(0));
                continue;
            }
            HeldPosition first = lots.get(0);
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal mv = null;
            BigDecimal weightedPriceSum = BigDecimal.ZERO;
            BigDecimal absQtySum = BigDecimal.ZERO;
            boolean priceComplete = true;
            boolean mvSeen = false;
            for (HeldPosition lot : lots) {
                if (lot.quantity() != null) qty = qty.add(lot.quantity());
                if (lot.marketValue() != null) {
                    mv = mv == null ? lot.marketValue() : mv.add(lot.marketValue());
                    mvSeen = true;
                }
                if (lot.quantity() == null || lot.avgPrice() == null) {
                    priceComplete = false;
                } else {
                    weightedPriceSum = weightedPriceSum.add(lot.quantity().abs().multiply(lot.avgPrice()));
                    absQtySum = absQtySum.add(lot.quantity().abs());
                }
            }
            BigDecimal avgPrice = (priceComplete && absQtySum.signum() != 0)
                    ? weightedPriceSum.divide(absQtySum, MathContext.DECIMAL64) : null;
            out.add(new HeldPosition(first.symbol(), qty, avgPrice, mvSeen ? mv : null,
                    first.unrealizedPnl(), first.currency(), first.verdictId(), first.killCriteria(),
                    first.horizon(), first.thesisSnapshot(), first.initialStop(), first.activeStop(),
                    first.contextSource(), first.openedAt()));
        }
        return out;
    }
}
