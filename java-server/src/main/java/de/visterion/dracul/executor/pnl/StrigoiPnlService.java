package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorIndicators;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.marketdata.FxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Gathers the inputs of {@link StrigoiPnlCalculator} for one connection (spec 2026-10-06):
 * the book, its TRIM/TRIM_FILL rows (one query), the broker {@code marketPrice} from
 * {@code get_positions} (only when an OPEN trade exists), the last close as fallback (lazily,
 * per symbol) and the EUR rate from the FX cache. Read-only.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class StrigoiPnlService {

    private static final Logger log = LoggerFactory.getLogger(StrigoiPnlService.class);
    static final String EUR = "EUR";
    static final String FX_BASIS = "current";

    private final ExecutorPositionRepository positions;
    private final DecisionLogRepository decisionLog;
    private final ExecutionGateway gateway;
    private final ExecutorIndicators indicators;
    private final FxService fx;
    private final String instrumentCurrency;
    private final int atrPeriod;
    private final int swingPeriod;

    public StrigoiPnlService(ExecutorPositionRepository positions, DecisionLogRepository decisionLog,
            ExecutionGateway gateway, ExecutorIndicators indicators, FxService fx,
            @Value("${dracul.executor.instrument-currency:USD}") String instrumentCurrency,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.positions = positions;
        this.decisionLog = decisionLog;
        this.gateway = gateway;
        this.indicators = indicators;
        this.fx = fx;
        this.instrumentCurrency = instrumentCurrency;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    public StrigoiPnlOverview overview(String connection) {
        return new StrigoiPnlOverview(connection, EUR, FX_BASIS,
                StrigoiPnlCalculator.overview(priceBook(connection, null)));
    }

    public StrigoiPnlDetail detail(String connection, String strigoi) {
        List<StrigoiPnlCalculator.Priced> priced = priceBook(connection, strigoi);
        return new StrigoiPnlDetail(connection, EUR, FX_BASIS,
                StrigoiPnlCalculator.summarize(strigoi, priced), StrigoiPnlCalculator.sortedTrades(priced));
    }

    private List<StrigoiPnlCalculator.Priced> priceBook(String connection, String onlyStrigoi) {
        List<ExecutorPosition> book = positions.findBookForPnl(connection).stream()
                .filter(StrigoiPnlCalculator::isTrade)
                .filter(p -> onlyStrigoi == null || onlyStrigoi.equals(StrigoiPnlCalculator.strigoiOf(p)))
                .toList();
        if (book.isEmpty()) return List.of();

        Set<String> symbols = book.stream().map(ExecutorPosition::symbol).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<DecisionLog> legRows = decisionLog.findBySymbolsAndActions(symbols, StrigoiPnlCalculator.LEG_ACTIONS);
        boolean anyOpen = book.stream().anyMatch(p -> "OPEN".equals(p.status()));
        Map<String, BigDecimal> brokerPrices = anyOpen ? brokerPrices(connection) : Map.of();
        Map<String, BigDecimal> closes = new HashMap<>();
        Function<String, BigDecimal> lastClose = s -> closes.computeIfAbsent(s, this::lastClose);

        return StrigoiPnlCalculator.price(new StrigoiPnlCalculator.Inputs(book, legRows, brokerPrices,
                lastClose, eurConverter(), instrumentCurrency, Instant.now()));
    }

    /** Per-unit broker marketPrice by upper-cased symbol; empty (→ last-close fallback) when the
     *  broker read fails. Saxo reports one position per tranche — same price, first one wins. */
    private Map<String, BigDecimal> brokerPrices(String connection) {
        Map<String, BigDecimal> prices = new HashMap<>();
        try {
            for (BrokerPosition bp : gateway.positions(connection)) {
                if (bp.symbol() != null && bp.marketPrice() != null) {
                    prices.putIfAbsent(bp.symbol().toUpperCase(Locale.ROOT), bp.marketPrice());
                }
            }
        } catch (RuntimeException e) {
            log.warn("strigoi pnl: broker positions unavailable for {} — falling back to the last close: {}",
                    connection, e.toString());
        }
        return prices;
    }

    /** Agora's {@code currentClose} from get_indicators; read even when the ATR bundle is
     *  unavailable. Null on any failure — the trade then carries NO_PRICE, never a 0. */
    private BigDecimal lastClose(String symbol) {
        try {
            ExecutorIndicators.Levels levels = indicators.levels(symbol, atrPeriod, swingPeriod);
            return levels == null ? null : levels.referencePrice();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Cache-first: {@link FxService#convert} silently returns the UNconverted amount on a cache
     *  miss, so the rate is checked (and warmed once) here and a missing rate yields null (NO_FX). */
    private UnaryOperator<BigDecimal> eurConverter() {
        if (EUR.equalsIgnoreCase(instrumentCurrency)) return a -> a;
        if (!fx.hasRate(instrumentCurrency, EUR)) fx.warm(instrumentCurrency, EUR);
        if (!fx.hasRate(instrumentCurrency, EUR)) return a -> null;
        return a -> a == null ? null : fx.convert(a, instrumentCurrency, EUR);
    }
}
