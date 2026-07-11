package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotChartServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test
    void instrumentChart1yCallsGetOhlcWithDays365() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), any())).thenReturn(json("""
            {"bars":[
              {"date":"2025-07-11","open":100,"high":101,"low":99,"close":100,"volume":1000},
              {"date":"2025-07-12","open":100,"high":112,"low":99,"close":110,"volume":1200}]}"""));

        DepotChartService service = new DepotChartService(agora);
        var chart = service.instrumentChart("ACME", "1y");

        verify(agora).callTool(eq("get_ohlc"), argThatDaysIs365());
        assertThat(chart.symbol()).isEqualTo("ACME");
        assertThat(chart.range()).isEqualTo("1y");
        assertThat(chart.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2025-07-11", "2025-07-12");
        assertThat(chart.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(BigDecimal.valueOf(100), BigDecimal.valueOf(110));
    }

    private JsonNode argThatDaysIs365() {
        return org.mockito.ArgumentMatchers.argThat(args -> args != null && args.path("days").asInt() == 365);
    }

    @Test
    void instrumentChart1dCallsGetIntraday() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_intraday"), any())).thenReturn(json("""
            {"bars":[
              {"time":"2026-07-11T09:30:00Z","open":100,"high":101,"low":99,"close":100.5,"volume":500},
              {"time":"2026-07-11T09:35:00Z","open":100,"high":101,"low":99,"close":101.2,"volume":600}]}"""));

        DepotChartService service = new DepotChartService(agora);
        var chart = service.instrumentChart("ACME", "1d");

        verify(agora).callTool(eq("get_intraday"), org.mockito.ArgumentMatchers.argThat(args ->
                args != null
                        && "ACME".equals(args.path("symbol").asString())
                        && "5m".equals(args.path("interval").asString())
                        && "1d".equals(args.path("range").asString())));
        assertThat(chart.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2026-07-11T09:30:00Z", "2026-07-11T09:35:00Z");
        assertThat(chart.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(BigDecimal.valueOf(100.5), BigDecimal.valueOf(101.2));
    }

    @Test
    void instrumentChartInvalidRangeIs400() {
        AgoraClient agora = mock(AgoraClient.class);
        DepotChartService service = new DepotChartService(agora);

        assertThatThrownBy(() -> service.instrumentChart("ACME", "5y"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void depotCurveInvalidRangeIs400() {
        AgoraClient agora = mock(AgoraClient.class);
        DepotChartService service = new DepotChartService(agora);
        List<DepotPosition> positions = List.of(
                new DepotPosition("ACME", BigDecimal.TEN, null, null, null, null));

        assertThatThrownBy(() -> service.depotCurve("bogus", positions, BigDecimal.ZERO))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void depotCurveComposesTwoPositionsPlusCash() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "ACME".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-01","open":99,"high":101,"low":99,"close":100,"volume":1},
                      {"date":"2026-07-02","open":100,"high":112,"low":99,"close":110,"volume":1}]}"""));
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "OTHR".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-01","open":51,"high":52,"low":49,"close":50,"volume":1},
                      {"date":"2026-07-02","open":50,"high":41,"low":39,"close":40,"volume":1}]}"""));

        DepotChartService service = new DepotChartService(agora);
        List<DepotPosition> positions = List.of(
                new DepotPosition("ACME", BigDecimal.TEN, null, null, null, null),
                new DepotPosition("OTHR", BigDecimal.valueOf(5), null, null, null, null));

        var curve = service.depotCurve("1w", positions, BigDecimal.valueOf(1000));

        assertThat(curve.partial()).isFalse();
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2026-07-01", "2026-07-02");
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(new BigDecimal("2250.00"), new BigDecimal("2300.00"));
        assertThat(curve.relative()).extracting(DepotChartService.RelativePoint::pct)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("2.22"));
    }

    @Test
    void failingSymbolIsSkippedAndMarksPartial() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "ACME".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-01","open":99,"high":101,"low":99,"close":100,"volume":1},
                      {"date":"2026-07-02","open":100,"high":112,"low":99,"close":110,"volume":1}]}"""));
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "BROKEN".equals(args.path("symbol").asString()))))
                .thenThrow(new AgoraUnavailableException("agora down"));

        DepotChartService service = new DepotChartService(agora);
        List<DepotPosition> positions = List.of(
                new DepotPosition("ACME", BigDecimal.TEN, null, null, null, null),
                new DepotPosition("BROKEN", BigDecimal.valueOf(5), null, null, null, null));

        var curve = service.depotCurve("1w", positions, BigDecimal.valueOf(1000));

        assertThat(curve.partial()).isTrue();
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2026-07-01", "2026-07-02");
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("2100.00"));
    }

    @Test
    void emptyBarsSymbolIsSkippedAndMarksPartial() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "ACME".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-01","open":99,"high":101,"low":99,"close":100,"volume":1},
                      {"date":"2026-07-02","open":100,"high":112,"low":99,"close":110,"volume":1}]}"""));
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "EMPTY".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[]}"""));

        DepotChartService service = new DepotChartService(agora);
        List<DepotPosition> positions = List.of(
                new DepotPosition("ACME", BigDecimal.TEN, null, null, null, null),
                new DepotPosition("EMPTY", BigDecimal.valueOf(5), null, null, null, null));

        var curve = service.depotCurve("1w", positions, BigDecimal.valueOf(1000));

        assertThat(curve.partial()).isTrue();
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2026-07-01", "2026-07-02");
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("2100.00"));
    }

    @Test
    void datesAreAlignedByIntersection() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "ACME".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-01","open":99,"high":101,"low":99,"close":100,"volume":1},
                      {"date":"2026-07-02","open":100,"high":112,"low":99,"close":110,"volume":1},
                      {"date":"2026-07-03","open":110,"high":120,"low":109,"close":115,"volume":1}]}"""));
        when(agora.callTool(eq("get_ohlc"), org.mockito.ArgumentMatchers.argThat(
                args -> args != null && "OTHR".equals(args.path("symbol").asString()))))
                .thenReturn(json("""
                    {"bars":[
                      {"date":"2026-07-02","open":50,"high":41,"low":39,"close":40,"volume":1}]}"""));

        DepotChartService service = new DepotChartService(agora);
        List<DepotPosition> positions = List.of(
                new DepotPosition("ACME", BigDecimal.ONE, null, null, null, null),
                new DepotPosition("OTHR", BigDecimal.ONE, null, null, null, null));

        var curve = service.depotCurve("1w", positions, BigDecimal.ZERO);

        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::t)
                .containsExactly("2026-07-02");
        assertThat(curve.points()).extracting(DepotChartService.ChartPoint::value)
                .containsExactly(new BigDecimal("150.00"));
    }
}
