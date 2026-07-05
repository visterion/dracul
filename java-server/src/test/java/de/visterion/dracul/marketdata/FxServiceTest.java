package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void sameCurrencyIsIdentityAndNeverCallsAgora() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        FxService fx = new FxService(agora);
        assertThat(fx.convert(new BigDecimal("100"), "USD", "USD")).isEqualByComparingTo("100");
        verify(agora, never()).callTool(any(), any());
    }

    @Test void convertWithoutWarmIsIdentityAndDoesNoFetch() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        FxService fx = new FxService(agora);
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("100");
        verify(agora, never()).callTool(any(), any());
    }

    @Test void warmThenConvertUsesAgoraRate() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_fx_rate"), any()))
                .thenReturn(json("{\"from\":\"USD\",\"to\":\"EUR\",\"rate\":0.90}"));
        FxService fx = new FxService(agora);
        fx.warm("USD", "EUR");
        // 100 USD * 0.90 = 90.00 (scale 4)
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("90.0000");
        verify(agora, times(1)).callTool(eq("get_fx_rate"), any());
    }

    @Test void warmPassesFromAndToArgs() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("get_fx_rate"), any()))
                .thenReturn(json("{\"from\":\"USD\",\"to\":\"EUR\",\"rate\":0.90}"));
        var cap = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        new FxService(agora).warm("usd", "eur");
        verify(agora).callTool(eq("get_fx_rate"), cap.capture());
        assertThat(cap.getValue().get("from").asString()).isEqualTo("usd");
        assertThat(cap.getValue().get("to").asString()).isEqualTo("eur");
    }

    @Test void warmKeepsLastKnownOnAgoraUnavailable() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        // first warm succeeds, caches 0.90
        when(agora.callTool(eq("get_fx_rate"), any()))
                .thenReturn(json("{\"from\":\"USD\",\"to\":\"EUR\",\"rate\":0.90}"))
                .thenThrow(new AgoraUnavailableException("agora down"));
        FxService fx = new FxService(agora);
        fx.warm("USD", "EUR");
        fx.warm("USD", "EUR"); // throws internally, must not propagate, keeps 0.90
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("90.0000");
    }

    @Test void nullClientIsSafeForConvert() {
        // the @Primary test stub uses new FxService(null); convert must not touch the client
        FxService fx = new FxService(null);
        assertThat(fx.convert(new BigDecimal("100"), "USD", "EUR")).isEqualByComparingTo("100");
    }
}
