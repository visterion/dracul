package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.ExecutionGateway;
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

    private EntryContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new EntryContextAssembler(agora, gateway, fx, positionRepo, cooldownRepo,
                signalRepo, mapper, "depot-1", 22, 20,
                new BigDecimal("10000"), 10, "USD", CLOCK);

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
        return new ExecutorPosition(1L, "depot-1", symbol, "BUY", qty, entryPrice,
                entryPrice, activeStop, 1, null, List.of(), sourceSignalId, "agent", "2026-07-01",
                null, "OPEN", "brk-1", null, null, 0, null, null, null, null,
                "stop-1", null, null, null, null, 0, null, null);
    }

    private JsonNode profileResponse(String sector, String finnhubIndustry, String gicsSector) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode profile = root.putObject("profile");
        if (sector != null) profile.put("sector", sector);
        if (finnhubIndustry != null) profile.put("finnhubIndustry", finnhubIndustry);
        if (gicsSector != null) profile.put("gicsSector", gicsSector);
        return root;
    }

    @Test
    void happyPath_completeContextNoMissing() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

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

    @Test
    void agoraIndicatorsUnavailable_missingPriceAtrAdv20() {
        when(agora.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException("down"));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("price", "atr", "adv20_notional");
        assertThat(ctx.price()).isNull();
        assertThat(ctx.atr()).isNull();
        assertThat(ctx.adv20Notional()).isNull();
    }

    @Test
    void profileWithoutSectorField_missingSector() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse(null, null, null));

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
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

        ExecutorSignal sig = signal("ACME", null, "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("signal_reference");
    }

    @Test
    void differingCurrenciesNoRate_missingContainsFx() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));
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
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));
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
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

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
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

        ExecutorPosition msft = openPosition("MSFT", BigDecimal.TEN, new BigDecimal("300.00"),
                new BigDecimal("290.00"), "sig-1");
        ExecutorPosition aapl = openPosition("AAPL", new BigDecimal("5"), new BigDecimal("150.00"),
                new BigDecimal("145.00"), "sig-2");
        when(positionRepo.findOpen()).thenReturn(List.of(msft, aapl));

        ExecutorSignal spinoffSignal = new ExecutorSignal("sig-1", "strigoi-spin", "v1", "MSFT", "BUY", 0.8,
                "SPINOFF", List.of(), "swing", new BigDecimal("300.00"), "PENDING", "2026-07-01T00:00:00Z");
        when(signalRepo.findById("sig-1")).thenReturn(spinoffSignal);
        when(signalRepo.findById("sig-2")).thenReturn(null);

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.openExposure()).isEqualByComparingTo("3750.00"); // 10*300.00 + 5*150.00
        assertThat(ctx.openHeat()).isEqualByComparingTo("125.00");
        assertThat(ctx.openMechanisms()).containsExactly(java.util.Map.entry("MSFT", "SPINOFF"));
    }

    @Test
    void tradingDayAge_previousFridayToMonday_isOne() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

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
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));

        EntryContext ctx = assembler.assembleForSymbol("ACME");

        assertThat(ctx.missing()).doesNotContain("signal_reference", "signal_age");
        assertThat(ctx.signalAgeTradingDays()).isEqualTo(-1L);
        assertThat(ctx.price()).isEqualByComparingTo("100.00");
        assertThat(ctx.atr()).isEqualByComparingTo("2.50");
        assertThat(ctx.candidateSector()).isEqualTo("Technology");
        assertThat(ctx.missing()).isEmpty();
    }

    @Test
    void accountWithNullCashIsMarkedMissing() {
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(indicatorsResponse(
                new BigDecimal("2.50"), new BigDecimal("95.00"), new BigDecimal("1000000"),
                new BigDecimal("101.00"), new BigDecimal("100.00")));
        when(agora.callTool(eq("get_company_profile"), any())).thenReturn(profileResponse("Technology", null, null));
        // Gateway returns account with null cash but valid buyingPower and currency
        when(gateway.account("depot-1"))
                .thenReturn(new AccountSnapshot(null, new BigDecimal("50000"), "USD"));

        ExecutorSignal sig = signal("ACME", new BigDecimal("100.00"), "2026-07-10T00:00:00Z");

        EntryContext ctx = assembler.assemble(sig);

        assertThat(ctx.missing()).contains("account");
    }
}
