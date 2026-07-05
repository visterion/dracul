package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.echo.EarningsObservation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraEarningsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void recentMapsRowsAndHandlesOmittedFields() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_earnings_window"), any())).thenReturn(json(
                "{\"earnings\":[" +
                // full row
                "{\"symbol\":\"aapl\",\"date\":\"2026-06-30\",\"epsActual\":1.65,\"epsEstimate\":1.50," +
                "\"epsSurprisePct\":10.0,\"revenueActual\":1000,\"revenueEstimate\":900}," +
                // nulls OMITTED, not emitted: only symbol+date
                "{\"symbol\":\"MSFT\",\"date\":\"2026-06-29\"}," +
                // date omitted -> row skipped
                "{\"symbol\":\"NODATE\",\"epsActual\":2.0}]}"));
        AgoraEarnings earnings = new AgoraEarnings(client);

        DataSourceResult<EarningsObservation> r =
                earnings.recent(LocalDate.parse("2026-06-23"), LocalDate.parse("2026-06-30"));
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().source()).isEqualTo("agora");
        assertThat(r.items()).hasSize(2);

        EarningsObservation aapl = r.items().get(0);
        assertThat(aapl.symbol()).isEqualTo("AAPL");             // uppercased
        assertThat(aapl.companyName()).isEqualTo("AAPL");        // window tool has no company name
        assertThat(aapl.reportDate()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(aapl.epsActual()).isEqualByComparingTo("1.65");
        assertThat(aapl.epsEstimate()).isEqualByComparingTo("1.50");
        assertThat(aapl.epsSurprisePercent()).isEqualByComparingTo("10.0");
        assertThat(aapl.revenueActual()).isEqualByComparingTo("1000");
        assertThat(aapl.revenueEstimate()).isEqualByComparingTo("900");

        EarningsObservation msft = r.items().get(1);
        assertThat(msft.epsActual()).isNull();                   // omitted -> null
        assertThat(msft.epsSurprisePercent()).isNull();
        assertThat(msft.revenueActual()).isNull();
    }

    @Test void recentSendsWindowArgs() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_earnings_window"), any())).thenReturn(json("{\"earnings\":[]}"));
        new AgoraEarnings(client).recent(LocalDate.parse("2026-06-23"), LocalDate.parse("2026-06-30"));
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_earnings_window"), args.capture());
        assertThat(args.getValue().path("from").asString()).isEqualTo("2026-06-23");
        assertThat(args.getValue().path("to").asString()).isEqualTo("2026-06-30");
    }

    @Test void recentUnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_earnings_window"), any())).thenThrow(new AgoraUnavailableException("down"));
        DataSourceResult<EarningsObservation> r =
                new AgoraEarnings(client).recent(LocalDate.now().minusDays(7), LocalDate.now());
        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().source()).isEqualTo("agora");
    }

    @Test void nextEarningsDatePicksEarliestStrictlyFutureDate() {
        LocalDate today = LocalDate.now();
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_earnings_calendar"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"earnings\":[" +
                "{\"date\":\"" + today + "\"},"  +                      // today -> not strictly future
                "{\"date\":\"" + today.plusDays(45) + "\",\"epsEstimate\":1.7}," +
                "{\"date\":\"" + today.plusDays(20) + "\"}]}"));
        AgoraEarnings earnings = new AgoraEarnings(client);

        Optional<LocalDate> next = earnings.nextEarningsDate("AAPL");
        assertThat(next).contains(today.plusDays(20));

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_earnings_calendar"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("AAPL");
        assertThat(args.getValue().path("from").asString()).isEqualTo(today.toString());
        assertThat(args.getValue().path("to").asString()).isEqualTo(today.plusDays(90).toString());
    }

    @Test void nextEarningsDateEmptyWhenNoFutureRowsOrFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_earnings_calendar"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"earnings\":[]}"));
        assertThat(new AgoraEarnings(client).nextEarningsDate("AAPL")).isEmpty();

        AgoraClient down = Mockito.mock(AgoraClient.class);
        when(down.callTool(eq("get_earnings_calendar"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraEarnings(down).nextEarningsDate("AAPL")).isEmpty();
    }
}
