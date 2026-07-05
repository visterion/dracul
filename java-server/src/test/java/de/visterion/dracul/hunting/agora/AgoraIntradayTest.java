package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraIntradayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void candlesMapsBarsOldestFirstAndSendsIntervalRange() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_intraday"), any())).thenReturn(json(
                "{\"symbol\":\"ACME\",\"bars\":[" +
                "{\"time\":\"2026-06-30T13:30:00Z\",\"open\":100,\"high\":101,\"low\":99,\"close\":100.5,\"volume\":1000}," +
                "{\"time\":\"2026-06-30T13:35:00Z\",\"open\":100.5,\"high\":106,\"low\":100,\"close\":105.5,\"volume\":4000}]}"));
        AgoraIntraday intraday = new AgoraIntraday(client);

        IntradayCandles c = intraday.candles("ACME");
        assertThat(c.closes()).containsExactly(new BigDecimal("100.5"), new BigDecimal("105.5"));
        assertThat(c.volumes()).containsExactly(1000L, 4000L);

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_intraday"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("ACME");
        assertThat(args.getValue().path("interval").asString()).isEqualTo("5m");
        assertThat(args.getValue().path("range").asString()).isEqualTo("1d");
    }

    @Test void candlesSkipsBarsWithNullClose() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_intraday"), any())).thenReturn(json(
                "{\"symbol\":\"ACME\",\"bars\":[" +
                "{\"time\":\"2026-06-30T13:30:00Z\",\"open\":100,\"high\":101,\"low\":99,\"close\":null,\"volume\":500}," +
                "{\"time\":\"2026-06-30T13:35:00Z\",\"open\":100,\"high\":101,\"low\":99,\"close\":100.5,\"volume\":1000}]}"));
        IntradayCandles c = new AgoraIntraday(client).candles("ACME");
        assertThat(c.closes()).containsExactly(new BigDecimal("100.5"));
        assertThat(c.volumes()).containsExactly(1000L);
    }

    @Test void candlesSkipsBarWithPresentCloseButNullVolume() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_intraday"), any())).thenReturn(json(
                "{\"symbol\":\"ACME\",\"bars\":[" +
                "{\"time\":\"2026-06-30T13:30:00Z\",\"open\":100,\"high\":101,\"low\":99,\"close\":100.5,\"volume\":null}," +
                "{\"time\":\"2026-06-30T13:35:00Z\",\"open\":100,\"high\":106,\"low\":100,\"close\":105.5,\"volume\":4000}]}"));
        IntradayCandles c = new AgoraIntraday(client).candles("ACME");
        assertThat(c.closes()).containsExactly(new BigDecimal("105.5"));
        assertThat(c.volumes()).containsExactly(4000L);
    }

    @Test void candlesEmptyOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_intraday"), any())).thenThrow(new AgoraUnavailableException("down"));
        IntradayCandles c = new AgoraIntraday(client).candles("ACME");
        assertThat(c.isEmpty()).isTrue();
        assertThat(c.volumes()).isEmpty();
    }
}
