package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.hunting.agora.SectorCascade;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.FxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test: mocked Agora client, gateway, repos, real ObjectMapper, fixed Clock. */
class EntryContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z"); // Monday
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AgoraClient agora = mock(AgoraClient.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final FxService fx = mock(FxService.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SectorCascade sectorCascade = mock(SectorCascade.class);

    private EntryContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new EntryContextAssembler(agora, gateway, fx, positionRepo, cooldownRepo,
                signalRepo, mapper, sectorCascade, "depot-1", 22, 20, 5,
                new BigDecimal("10000"), 10, "USD", ConvictionProfile.defaults(), CLOCK);

        when(gateway.account("depot-1"))
                .thenReturn(new AccountSnapshot(new BigDecimal("50000"), new BigDecimal("50000"), "USD"));
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(cooldownRepo.active(any())).thenReturn(List.of());
        when(positionRepo.countEnteredSince(any())).thenReturn(3);
        // identity fx: instrument == account currency in these tests
        when(fx.convert(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ExecutorSignal signal(String symbol, BigDecimal referencePrice, String createdAt) {
        return new ExecutorSignal("sig-1", "strigoi-spin", "v1", symbol, "BUY", 0.8,
                "spin-off", List.of("EARNINGS_MISS"), "swing", referencePrice, "PENDING", createdAt);
    }

    private JsonNode indicatorsResponse(BigDecimal atr, BigDecimal swingLow, BigDecimal adv20, BigDecimal dayHigh,
            BigDecimal currentClose) {
        ObjectNode root = mapper.createObjectNode();
        root.put("symbol", "ACME");
        root.put("currentClose", currentClose.toPlainString());
        root.put("available", true);
        ArrayNode values = root.putArray("values");
        addValue(values, "atr", atr);
        addValue(values, "swing_low", swingLow);
        addValue(values, "adv20", adv20);
        addValue(values, "day_high", dayHigh);
        return root;
    }

    /** The same body plus Agora's live-bar high (SP8's {@code currentHigh}), which the assembler
     *  must prefer over the completed-bar {@code day_high} indicator value. */
    private JsonNode indicatorsResponseWithCurrentHigh(BigDecimal atr, BigDecimal swingLow,
            BigDecimal adv20, BigDecimal dayHigh, BigDecimal currentClose, BigDecimal currentHigh) {
        ObjectNode root = (ObjectNode) indicatorsResponse(atr, swingLow, adv20, dayHigh, currentClose);
        root.put("currentHigh", currentHigh.toPlainString());
        return root;
    }

    private void addValue(ArrayNode values, String label, BigDecimal value) {
        ObjectNode v = values.addObject();
        v.put("label", label);
        if (value == null) {
            v.put("available", false);
        } else {
            v.put("available", true);
            v.put("value", value.toPlainString());
        }
    }

    private ExecutorPosition openPosition(String symbol, BigDecimal qty, BigDecimal entryPrice,
            BigDecimal activeStop, String sourceSignalId) {
        return ExecutorPositionFixtures.withoutKillLevel(1L, "depot-1", symbol, "BUY", qty, entryPrice,
                entryPrice, activeStop, 1, null, List.of(), sourceSignalId, "agent", "2026-07-01",
                null, "OPEN", "brk-1", null, null, 0, null, null, null, null,
                "stop-1", null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
    }

    @Test
    void happyPath_completeContextNoMissing() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).isEmpty();
        assertThat(ctx.price()).isEqualByComparingTo("100.00");
        assertThat(ctx.atr()).isEqualByComparingTo("2.50");
        assertThat(ctx.swingLow()).isEqualByComparingTo("95.00");
        assertThat(ctx.dayHigh()).isEqualByComparingTo("101.00");
        assertThat(ctx.adv20Notional()).isEqualByComparingTo(new BigDecimal("1000000").multiply(new BigDecimal("100.00")));
        assertThat(ctx.candidateSector()).isEqualTo("Technology");
        assertThat(ctx.entriesThisWeek()).isEqualTo(3);
        assertThat(ctx.trancheAmount()).isEqualByComparingTo(new BigDecimal("10000").divide(new BigDecimal("10")));
        assertThat(ctx.account()).isNotNull();
        assertThat(ctx.signalAgeTradingDays()).isGreaterThanOrEqualTo(0);
    }

    /** SP8: once Agora computes indicator values over COMPLETED bars only, the {@code day_high}
     *  indicator is the previous session's high while an exchange is open. {@code entry_day_high}
     *  must keep meaning the LIVE day's high (Tranche2Detector compares against it), so the
     *  assembler prefers Agora's {@code currentHigh}, which is read off the live last bar. */
    @Test
    void dayHighPrefersCurrentHighWhenAgoraProvidesIt() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponseWithCurrentHigh(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00"), new BigDecimal("103.50")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(
                signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.dayHigh()).isEqualByComparingTo("103.50");
        assertThat(ctx.missing()).isEmpty();
    }

    /** (regression) An Agora that has not shipped the completed-bar guard yet sends no
     *  {@code currentHigh}, and the assembler must fall back to the {@code day_high} indicator
     *  value exactly as before -- Agora deploys first, Dracul second, and the window between the
     *  two deploys runs on this branch. */
    @Test
    void dayHighFallsBackToTheDayHighIndicator() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(
                signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.dayHigh()).isEqualByComparingTo("101.00");
    }

    @Test
    void agoraIndicatorsUnavailable_missingPriceAtrAdv20() {
        when(agora.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException("down"));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("price", "atr", "adv20_notional");
        assertThat(ctx.price()).isNull();
        assertThat(ctx.atr()).isNull();
        assertThat(ctx.adv20Notional()).isNull();
    }

    /** A young listing: Agora answered (no outage, no exception) but no spec produced a value, so
     *  the payload carries a top-level {@code available:false}. That body now reaches the assembler
     *  and the absent indicators must still land in {@code missing} so the DATA_UNAVAILABLE
     *  pre-veto fires — a silently-null ATR would otherwise reach position sizing. */
    @Test
    void topLevelUnavailableIndicators_stillMissingAtrAndAdv20() {
        ObjectNode root = mapper.createObjectNode();
        root.put("symbol", "SYNTH");
        root.put("currentClose", "143.21");
        root.put("available", false);
        ArrayNode values = root.putArray("values");
        addValue(values, "atr", null);
        addValue(values, "swing_low", null);
        addValue(values, "adv20", null);
        addValue(values, "day_high", null);
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(root);
        when(sectorCascade.resolve("SYNTH")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(signal("SYNTH", new BigDecimal("143.21"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.missing()).contains("atr", "adv20_notional");
        assertThat(ctx.atr()).isNull();
        assertThat(ctx.swingLow()).isNull();
        assertThat(ctx.adv20Notional()).isNull();
        // the close IS real data — Agora had bars, just not enough for any indicator window
        assertThat(ctx.price()).isEqualByComparingTo("143.21");
        assertThat(ctx.missing()).doesNotContain("price");
    }

    @Test
    void profileWithoutSectorField_missingSector() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn(null);

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("sector");
        assertThat(ctx.candidateSector()).isNull();
    }

    @Test
    void nullReferencePrice_missingSignalReference() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        ExecutorSignal sig = signal("ACME", null, "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("signal_reference");
    }

    @Test
    void differingCurrenciesNoRate_missingContainsFx() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(gateway.account("depot-1"))
                .thenReturn(new AccountSnapshot(new BigDecimal("50000"), new BigDecimal("50000"), "EUR"));
        // fx is a mock: hasRate() is not stubbed here, so it defaults to false — no cached rate.

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("fx");
    }

    @Test
    void sameCurrencies_missingDoesNotContainFx() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        // account currency USD == instrumentCurrency USD (setUp default) — fx.hasRate is not
        // even consulted for identical currencies.

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).doesNotContain("fx");
    }

    @Test
    void garbageCreatedAt_ageMinusOneAndMissingSignalAge() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "not-a-date");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.signalAgeTradingDays()).isEqualTo(-1L);
        assertThat(ctx.missing()).contains("signal_age");
    }

    @Test
    void openPositions_aggregateExposureHeatAndMechanisms() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        ExecutorPosition msft = openPosition("BETA", BigDecimal.TEN, new BigDecimal("300.00"),
                new BigDecimal("290.00"), "sig-1");
        ExecutorPosition aapl = openPosition("GAMMA", new BigDecimal("5"), new BigDecimal("150.00"),
                new BigDecimal("145.00"), "sig-2");
        when(positionRepo.findOpen()).thenReturn(List.of(msft, aapl));

        ExecutorSignal spinoffSignal = new ExecutorSignal("sig-1", "strigoi-spin", "v1", "BETA", "BUY", 0.8,
                "SPINOFF", List.of(), "swing", new BigDecimal("300.00"), "PENDING", "2026-07-01T00:00:00Z");
        when(signalRepo.findById("sig-1")).thenReturn(spinoffSignal);
        when(signalRepo.findById("sig-2")).thenReturn(null);

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.openExposure()).isEqualByComparingTo("3750.00"); // 10*300.00 + 5*150.00
        assertThat(ctx.openHeat()).isEqualByComparingTo("125.00");
        assertThat(ctx.openMechanisms()).containsExactly(java.util.Map.entry("BETA", "SPINOFF"));
    }

    @Test
    void openPositions_exposureByMechanismUsesPositionSignalAndFx() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        // 0.8601 instrument -> account, rounded to 4 places per product like FxService.convert
        when(fx.convert(any(), anyString(), anyString())).thenAnswer(inv -> {
            BigDecimal amount = inv.getArgument(0);
            return amount.multiply(new BigDecimal("0.8601")).setScale(4, java.math.RoundingMode.HALF_UP);
        });

        ExecutorPosition merger = openPosition("ACME", BigDecimal.TEN, new BigDecimal("100.00"),
                new BigDecimal("95.00"), "sig-merger");
        ExecutorPosition pead = openPosition("ACME", BigDecimal.TEN, new BigDecimal("100.00"),
                new BigDecimal("95.00"), "sig-pead");
        ExecutorPosition unresolved = openPosition("BETA", BigDecimal.ONE, new BigDecimal("50.00"),
                new BigDecimal("45.00"), "sig-missing");
        ExecutorPosition nullQty = openPosition("GAMMA", null, new BigDecimal("50.00"),
                new BigDecimal("45.00"), "sig-pead");
        when(positionRepo.findOpen()).thenReturn(List.of(merger, pead, unresolved, nullQty));
        when(signalRepo.findById("sig-merger")).thenReturn(new ExecutorSignal("sig-merger", "strigoi-merger",
                "v1", "ACME", "BUY", 0.7, "merger_arb", List.of(), "3m", new BigDecimal("100.00"),
                "PENDING", "2026-07-01T00:00:00Z"));
        when(signalRepo.findById("sig-pead")).thenReturn(new ExecutorSignal("sig-pead", "strigoi-echo",
                "v1", "ACME", "BUY", 0.7, "PEAD", List.of(), "3m", new BigDecimal("100.00"),
                "PENDING", "2026-07-01T00:00:00Z"));
        when(signalRepo.findById("sig-missing")).thenReturn(null);

        EntryContext ctx = assembler.assemble(signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.openExposureByMechanism()).containsOnlyKeys("MERGER_ARB", "PEAD", "UNRESOLVED");
        assertThat(ctx.openExposureByMechanism().get("MERGER_ARB")).isEqualByComparingTo("860.1000");
        assertThat(ctx.openExposureByMechanism().get("PEAD")).isEqualByComparingTo("860.1000");
        assertThat(ctx.openExposureByMechanism().get("UNRESOLVED")).isEqualByComparingTo("43.0050");
        // the buckets are the same numbers openExposure is made of
        assertThat(ctx.openExposure()).isEqualByComparingTo("1763.2050");
    }

    @Test
    void tradingDayAge_previousFridayToMonday_isOne() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        // NOW = 2026-07-13 (Monday); previous Friday = 2026-07-10
        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T09:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.signalAgeTradingDays()).isEqualTo(1L);
        assertThat(ctx.missing()).doesNotContain("signal_age");
    }

    @Test
    void assembleForSymbol_signalReferenceAndAgeNotMandatory() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assembleForSymbol("ACME");

        assertThat(ctx.missing()).doesNotContain("signal_reference", "signal_age");
        assertThat(ctx.signalAgeTradingDays()).isEqualTo(-1L);
        assertThat(ctx.price()).isEqualByComparingTo("100.00");
        assertThat(ctx.atr()).isEqualByComparingTo("2.50");
        assertThat(ctx.candidateSector()).isEqualTo("Technology");
        assertThat(ctx.missing()).isEmpty();
    }

    private JsonNode quoteResponse(String currency) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode quotes = root.putArray("quotes");
        ObjectNode q = quotes.addObject();
        q.put("symbol", "ACME");
        q.put("price", "100.00");
        if (currency != null) q.put("currency", currency);
        return root;
    }

    @Test
    void quoteCurrencyThreadedFromGetQuote() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(agora.callTool(eq("get_quote"), any())).thenReturn(quoteResponse("EUR"));

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.quoteCurrency()).isEqualTo("EUR");
    }

    @Test
    void missingQuoteCurrencyStaysNull_notCoercedToUsd() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(agora.callTool(eq("get_quote"), any())).thenReturn(quoteResponse(null)); // no currency field

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        // Null-preserving: a missing currency must NOT become "USD" (unlike AgoraMarketData.resolve).
        assertThat(ctx.quoteCurrency()).isNull();
    }

    @Test
    void agoraQuoteUnavailable_quoteCurrencyNull() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(agora.callTool(eq("get_quote"), any())).thenThrow(new AgoraUnavailableException("down"));

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.quoteCurrency()).isNull();
    }

    @Test
    void accountWithNullCashIsMarkedMissing() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        // Gateway returns account with null cash but valid buyingPower and currency
        when(gateway.account("depot-1"))
                .thenReturn(new AccountSnapshot(null, new BigDecimal("50000"), "USD"));

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("account");
    }

    /** Test 23 (assembler half) + test 24. Mutation: drop the explicit `atr_short` label from
     *  fetchIndicators' request, or let a missing atr_short null out atrEff. */
    @Test
    void requestsShortAtrWithExplicitLabelAndExposesAtrEff() {
        ObjectNode root = mapper.createObjectNode();
        root.put("symbol", "ACME");
        root.put("currentClose", "100.00");
        root.put("available", true);
        ArrayNode values = root.putArray("values");
        addValue(values, "atr", new BigDecimal("2.50"));
        addValue(values, "atr_short", new BigDecimal("4.00"));
        addValue(values, "swing_low", new BigDecimal("95.00"));
        addValue(values, "adv20", new BigDecimal("1000000"));
        addValue(values, "day_high", new BigDecimal("101.00"));
        org.mockito.ArgumentCaptor<ObjectNode> args = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(root);
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        org.mockito.Mockito.verify(agora).callTool(eq("get_indicators"), args.capture());
        JsonNode specs = args.getValue().path("indicators");
        boolean hasLabelledShortAtr = false;
        for (JsonNode s : specs) {
            if ("atr".equals(s.path("name").asString()) && "atr_short".equals(s.path("label").asString(""))) {
                assertThat(s.path("params").path("period").asInt()).isEqualTo(5);
                hasLabelledShortAtr = true;
            }
        }
        assertThat(hasLabelledShortAtr).as("atr_short spec present with explicit label").isTrue();

        assertThat(ctx.atr()).isEqualByComparingTo("2.50");
        assertThat(ctx.atrShort()).isEqualByComparingTo("4.00");
        assertThat(ctx.atrEff()).isEqualByComparingTo("4.00");
        assertThat(ctx.missing()).isEmpty();
    }

    /** A missing atr_short is fail-soft: atrEff falls back to ATR22 and the symbol is NOT flagged
     *  as missing mandatory data. Mutation: add "atr_short" to `missing`. */
    @Test
    void missingShortAtrIsFailSoftAndNeverMandatory() {
        ObjectNode root = mapper.createObjectNode();
        root.put("symbol", "ACME");
        root.put("currentClose", "100.00");
        root.put("available", true);
        ArrayNode values = root.putArray("values");
        addValue(values, "atr", new BigDecimal("2.50"));
        addValue(values, "atr_short", null);
        addValue(values, "swing_low", new BigDecimal("95.00"));
        addValue(values, "adv20", new BigDecimal("1000000"));
        addValue(values, "day_high", new BigDecimal("101.00"));
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(root);
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.atrShort()).isNull();
        assertThat(ctx.atrEff()).isEqualByComparingTo("2.50");
        assertThat(ctx.missing()).doesNotContain("atr_short");
        assertThat(ctx.missing()).isEmpty();
    }

    /** Spec 2026-10-03 §5.3 (R1 M5): the profile notional is total-budget x position-pct in the
     *  ACCOUNT currency, converted into the instrument currency exactly like trancheAmount. */
    @Test
    void profileNotionalIsFxConvertedFromTheAccountCurrency() {
        when(gateway.account("depot-1")).thenReturn(new AccountSnapshot(new BigDecimal("50000"),
                new BigDecimal("50000"), "EUR"));
        when(fx.hasRate("USD", "EUR")).thenReturn(true);
        when(fx.convert(any(), eq("EUR"), eq("USD")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("1.10")));
        when(fx.convert(any(), eq("USD"), eq("EUR")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("1.10"), 6,
                        java.math.RoundingMode.HALF_UP));
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");

        EntryContext ctx = assembler.assemble(
                signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        // 10000 EUR x 0.03 = 300 EUR -> 330 USD
        assertThat(ctx.profileNotional()).isEqualByComparingTo("330");
    }

    /** Spec 2026-10-03 §5.3 (R2 Minor 8): openHeat sums STANDARD positions only — the basket's
     *  ~14 % risk never consumes the heat of other strategies — and a STANDARD stop above entry
     *  still contributes its NEGATIVE heat exactly as before. */
    @Test
    void openHeatCountsStandardPositionsOnly() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(positionRepo.findOpen()).thenReturn(List.of(
                ExecutorPositionFixtures.conviction(openPosition("SYNA", new BigDecimal("10"),
                        new BigDecimal("100"), new BigDecimal("65"), null)),   // 350, excluded
                openPosition("SYNB", new BigDecimal("10"), new BigDecimal("50"),
                        new BigDecimal("45"), null),                           // +50
                openPosition("SYNC", new BigDecimal("10"), new BigDecimal("50"),
                        new BigDecimal("55"), null)));                         // -50

        EntryContext ctx = assembler.assemble(
                signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.openHeat()).isEqualByComparingTo("0");
        // exposure still counts every position (MECHANISM_BUDGET/BUDGET need the basket)
        assertThat(ctx.openExposure()).isEqualByComparingTo("2000");
    }

    /** Spec 2026-10-04 §3: heat counts STANDARD only — a MOMENTUM row is excluded like CONVICTION. */
    @Test
    void openHeatExcludesMomentumPositions() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
        when(positionRepo.findOpen()).thenReturn(List.of(
                ExecutorPositionFixtures.withProfileFields(openPosition("SYNA", new BigDecimal("10"),
                        new BigDecimal("100"), new BigDecimal("65"), null), ExitProfile.MOMENTUM,
                        null, null, null, false),                                   // 350, excluded
                openPosition("SYNB", new BigDecimal("10"), new BigDecimal("50"),
                        new BigDecimal("45"), null)));                              // +50

        EntryContext ctx = assembler.assemble(
                signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z"));

        assertThat(ctx.openHeat()).isEqualByComparingTo("50");
        assertThat(ctx.openExposure()).isEqualByComparingTo("1500");
    }

    private ExecutorSignal momentumSignal(String symbol) {
        return new ExecutorSignal("sig-m", "strigoi-momentum", "v1", symbol, "BUY", 0.5,
                "MOMENTUM_12_1", List.of("k"), "1 month (rebalance)", new BigDecimal("100.00"),
                "PENDING", "2026-07-10T00:00:00Z");
    }

    private ExecutorSignal source(String id, String mechanism) {
        return new ExecutorSignal(id, "hunter", "v1", "X", "BUY", 0.5, mechanism, List.of("k"),
                "1m", null, "ACCEPTED", "2026-07-01T00:00:00Z");
    }

    private void stubIndicators() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(sectorCascade.resolve("ACME")).thenReturn("Technology");
    }

    /** Spec 2026-10-04 §3: the MOMENTUM notional is total-budget x momentum position-pct. */
    @Test
    void momentumNotionalUsesTheMomentumPct() {
        stubIndicators();

        EntryContext ctx = assembler.assemble(momentumSignal("ACME"));

        // 10000 x 0.025 = 250 (identity fx)
        assertThat(ctx.profileNotional()).isEqualByComparingTo("250");
    }

    /** Spec §3 (R2 M1): for a MOMENTUM signal a flagged MOMENTUM row is capital being freed
     *  tonight — out of openExposure and openExposureByMechanism, whether its flatten is still
     *  to come (entries first) or already pending (maintenance first). STANDARD signals see it. */
    @Test
    void committedRebalanceExitsAreExcludedForMomentumSignalsOnly() {
        stubIndicators();
        ExecutorPosition flagged = ExecutorPositionFixtures.withRebalanceExitAt(
                ExecutorPositionFixtures.momentum(openPosition("SYNA", new BigDecimal("10"),
                        new BigDecimal("100"), new BigDecimal("65"), "src-a")),
                "2026-10-30 22:40:00+00");                                        // 1000
        ExecutorPosition flaggedPending = ExecutorPositionFixtures.withRebalanceExitAt(
                ExecutorPositionFixtures.momentum(ExecutorPositionFixtures.withoutKillLevel(2L,
                        "depot-1", "SYNB", "BUY", new BigDecimal("10"), new BigDecimal("40"),
                        new BigDecimal("40"), new BigDecimal("26"), 1, null, List.of(), "src-b",
                        "agent", "2026-07-01", null, "OPEN", "brk-2", null, null, 0, null, null,
                        null, null, "stop-2", null, null, null, null, 0, null, null, null,
                        "HARD_REBALANCE", "close-2", null, false, null, null)),
                "2026-10-30 22:40:00+00");                                        // 400, flatten pending
        ExecutorPosition held = ExecutorPositionFixtures.momentum(openPosition("SYNC",
                new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("33"), "src-c")); // 500
        ExecutorPosition standard = openPosition("SYND", new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("45"), "src-d");                                   // 500
        when(positionRepo.findOpen()).thenReturn(List.of(flagged, flaggedPending, held, standard));
        when(signalRepo.findById("src-a")).thenReturn(source("src-a", "MOMENTUM_12_1"));
        when(signalRepo.findById("src-b")).thenReturn(source("src-b", "MOMENTUM_12_1"));
        when(signalRepo.findById("src-c")).thenReturn(source("src-c", "MOMENTUM_12_1"));
        when(signalRepo.findById("src-d")).thenReturn(source("src-d", "PEAD"));

        EntryContext mom = assembler.assemble(momentumSignal("ACME"));
        assertThat(mom.openExposure()).isEqualByComparingTo("1000");
        assertThat(mom.openExposureByMechanism().get("MOMENTUM_12_1")).isEqualByComparingTo("500");
        assertThat(mom.openPositions()).hasSize(4);   // the list itself is untouched (REDUNDANCY)

        EntryContext std = assembler.assemble(signal("ACME", new BigDecimal("100.00"),
                "2026-07-10T00:00:00Z"));
        assertThat(std.openExposure()).isEqualByComparingTo("2400");
        assertThat(std.openExposureByMechanism().get("MOMENTUM_12_1")).isEqualByComparingTo("1900");
    }
}
