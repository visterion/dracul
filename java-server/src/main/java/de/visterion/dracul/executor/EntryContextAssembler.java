package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.FxService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single I/O layer for entry decisions: one place that talks to Agora, the broker gateway,
 * FxService and the executor repositories to assemble an {@link EntryContext}. Downstream
 * decision logic (veto rules, position sizing) consumes only the assembled record and never
 * performs I/O itself.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class EntryContextAssembler {

    private static final int PENDING_SIGNALS_LIMIT = 50;

    private final AgoraClient agora;
    private final ExecutionGateway gateway;
    private final FxService fx;
    private final ExecutorPositionRepository positionRepo;
    private final CooldownRepository cooldownRepo;
    private final ExecutorSignalRepository signalRepo;
    private final ObjectMapper mapper;
    private final String connection;
    private final int atrPeriod;
    private final int swingPeriod;
    private final BigDecimal totalBudget;
    private final int trancheCount;
    private final String instrumentCurrency;
    private final Clock clock;

    public EntryContextAssembler(AgoraClient agora, ExecutionGateway gateway, FxService fx,
            ExecutorPositionRepository positionRepo, CooldownRepository cooldownRepo,
            ExecutorSignalRepository signalRepo, ObjectMapper mapper,
            @Value("${dracul.executor.connection:saxo-sim}") String connection,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod,
            @Value("${dracul.executor.total-budget:10000}") BigDecimal totalBudget,
            @Value("${dracul.executor.tranche-count:10}") int trancheCount,
            @Value("${dracul.executor.instrument-currency:USD}") String instrumentCurrency,
            @Qualifier("executorClock") Clock clock) {
        this.agora = agora;
        this.gateway = gateway;
        this.fx = fx;
        this.positionRepo = positionRepo;
        this.cooldownRepo = cooldownRepo;
        this.signalRepo = signalRepo;
        this.mapper = mapper;
        this.connection = connection;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
        this.totalBudget = totalBudget;
        this.trancheCount = trancheCount;
        this.instrumentCurrency = instrumentCurrency;
        this.clock = clock;
    }

    public EntryContext assemble(ExecutorSignal signal) {
        return gather(signal.symbol(), signal);
    }

    /**
     * Lighter gathering path for tranche-2 adds ({@code add_tranche}), which have no
     * {@link ExecutorSignal} of their own (the add is keyed off an already-open position, not a
     * pending signal). Same I/O as {@link #assemble}, but {@code signal_reference}/{@code
     * signal_age} are not mandatory here — there is no signal to check freshness or a reference
     * price against, so {@link EntryContext#signalAgeTradingDays()} is left at -1 and neither
     * name is added to {@link EntryContext#missing()}.
     */
    public EntryContext assembleForSymbol(String symbol) {
        return gather(symbol, null);
    }

    private EntryContext gather(String symbol, ExecutorSignal signal) {
        List<String> missing = new ArrayList<>();

        AccountSnapshot account = fetchAccount(missing);
        String accountCurrency = account != null ? account.currency() : instrumentCurrency;

        Indicators ind = fetchIndicators(symbol, missing);
        String sector = fetchSector(symbol, missing);

        List<ExecutorPosition> openPositions = positionRepo.findOpen();
        List<Cooldown> activeCooldowns = cooldownRepo.active(clock.instant());
        List<ExecutorSignal> pendingSignals = signalRepo.findPending(PENDING_SIGNALS_LIMIT);

        int entriesThisWeek = positionRepo.countEnteredSince(startOfIsoWeek());

        long signalAgeTradingDays = -1L;
        if (signal != null) {
            signalAgeTradingDays = tradingDayAge(signal.createdAt(), missing);
            if (signal.referencePrice() == null) missing.add("signal_reference");
        }

        // FX: warm both directions once per assemble; convert() itself is cache-only.
        fx.warm(instrumentCurrency, accountCurrency);
        fx.warm(accountCurrency, instrumentCurrency);
        BigDecimal fxToAccount = fx.convert(BigDecimal.ONE, instrumentCurrency, accountCurrency);
        if (fxToAccount == null) fxToAccount = BigDecimal.ONE;

        BigDecimal trancheAmount = fx.convert(
                totalBudget.divide(BigDecimal.valueOf(trancheCount), 6, RoundingMode.HALF_UP),
                accountCurrency, instrumentCurrency);

        BigDecimal openExposure = BigDecimal.ZERO;
        BigDecimal openHeat = BigDecimal.ZERO;
        Map<String, String> openMechanisms = new LinkedHashMap<>();
        for (ExecutorPosition p : openPositions) {
            if (p.qty() != null && p.entryPrice() != null) {
                BigDecimal exposure = p.qty().multiply(p.entryPrice());
                openExposure = openExposure.add(fx.convert(exposure, instrumentCurrency, accountCurrency));
            }
            if (p.qty() != null && p.entryPrice() != null && p.activeStop() != null) {
                BigDecimal heat = p.qty().multiply(p.entryPrice().subtract(p.activeStop()));
                openHeat = openHeat.add(fx.convert(heat, instrumentCurrency, accountCurrency));
            }
            ExecutorSignal source = p.sourceSignalId() != null ? signalRepo.findById(p.sourceSignalId()) : null;
            if (source != null && source.mechanism() != null) {
                openMechanisms.put(p.symbol(), source.mechanism());
            }
        }

        return new EntryContext(
                account,
                ind.price(),
                ind.atr(),
                ind.swingLow(),
                ind.adv20Notional(),
                ind.dayHigh(),
                sector,
                openPositions,
                activeCooldowns,
                pendingSignals,
                entriesThisWeek,
                signalAgeTradingDays,
                trancheAmount,
                totalBudget,
                openExposure,
                openHeat,
                openMechanisms,
                fxToAccount,
                missing);
    }

    private AccountSnapshot fetchAccount(List<String> missing) {
        try {
            AccountSnapshot account = gateway.account(connection);
            if (account == null) {
                missing.add("account");
            }
            return account;
        } catch (BrokerUnavailableException e) {
            missing.add("account");
            return null;
        }
    }

    private record Indicators(BigDecimal price, BigDecimal atr, BigDecimal swingLow,
            BigDecimal adv20Notional, BigDecimal dayHigh) {
        static Indicators unavailable() { return new Indicators(null, null, null, null, null); }
    }

    private Indicators fetchIndicators(String symbol, List<String> missing) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode indicators = args.putArray("indicators");

        ObjectNode atrSpec = indicators.addObject();
        atrSpec.put("name", "atr");
        atrSpec.putObject("params").put("period", atrPeriod);

        ObjectNode swingSpec = indicators.addObject();
        swingSpec.put("name", "lowest");
        swingSpec.putObject("params").put("period", swingPeriod);
        swingSpec.put("label", "swing_low");

        ObjectNode adv20Spec = indicators.addObject();
        adv20Spec.put("name", "sma");
        adv20Spec.putObject("params").put("period", 20);
        adv20Spec.put("of", "volume");
        adv20Spec.put("label", "adv20");

        ObjectNode dayHighSpec = indicators.addObject();
        dayHighSpec.put("name", "highest");
        dayHighSpec.putObject("params").put("period", 1);
        dayHighSpec.put("of", "high");
        dayHighSpec.put("label", "day_high");

        JsonNode r;
        try {
            r = agora.callTool("get_indicators", args);
        } catch (AgoraUnavailableException e) {
            missing.add("price");
            missing.add("atr");
            missing.add("adv20_notional");
            return Indicators.unavailable();
        }

        BigDecimal price = decimal(r, "currentClose");
        BigDecimal atr = null, swingLow = null, adv20 = null, dayHigh = null;
        for (JsonNode v : r.path("values")) {
            String label = v.path("label").asString("");
            if (!v.path("available").asBoolean(false)) continue;
            BigDecimal value = decimal(v, "value");
            switch (label) {
                case "atr" -> atr = value;
                case "swing_low" -> swingLow = value;
                case "adv20" -> adv20 = value;
                case "day_high" -> dayHigh = value;
                default -> { /* ignore unknown labels */ }
            }
        }

        if (price == null) missing.add("price");
        if (atr == null) missing.add("atr");

        BigDecimal adv20Notional = (adv20 != null && price != null) ? adv20.multiply(price) : null;
        if (adv20Notional == null) missing.add("adv20_notional");

        return new Indicators(price, atr, swingLow, adv20Notional, dayHigh);
    }

    private String fetchSector(String symbol, List<String> missing) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        JsonNode r;
        try {
            r = agora.callTool("get_company_profile", args);
        } catch (AgoraUnavailableException e) {
            missing.add("sector");
            return null;
        }
        JsonNode profile = r.path("profile");
        String sector = firstNonBlank(
                profile.path("sector").asString(""),
                profile.path("finnhubIndustry").asString(""),
                profile.path("gicsSector").asString(""));
        if (sector == null) missing.add("sector");
        return sector;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private long tradingDayAge(String createdAt, List<String> missing) {
        LocalDate entry;
        try {
            if (createdAt == null || createdAt.isBlank()) throw new IllegalArgumentException("blank");
            entry = LocalDate.parse(createdAt.length() > 10 ? createdAt.substring(0, 10) : createdAt);
        } catch (RuntimeException e) {
            missing.add("signal_age");
            return -1L;
        }
        LocalDate today = LocalDate.now(clock);
        long days = 0;
        for (LocalDate d = entry.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) days++;
        }
        return days;
    }

    private java.time.Instant startOfIsoWeek() {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try { return new BigDecimal(v.asString()); } catch (NumberFormatException e) { return null; }
    }
}
