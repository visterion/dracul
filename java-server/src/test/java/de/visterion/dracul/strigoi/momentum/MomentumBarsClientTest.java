package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.marketdata.AgoraClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-04 §5.2/§8 V1: lowercase `roc`, percent values, series oldest → newest. */
class MomentumBarsClientTest {

    private final AgoraClient agora = mock(AgoraClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MomentumBarsClient client = new MomentumBarsClient(agora);

    @Test
    void asksForBothRocSeriesAndClassifiesEverySymbol() {
        when(agora.callTool(eq("get_indicators_batch"), any())).thenReturn(mapper.readTree("""
                {"results": [
                  {"symbol": "SYNA", "available": true, "asOf": "2026-10-30", "lastCompletedClose": 51.5,
                   "currentClose": 52.0,
                   "values": [
                     {"label": "roc231", "available": true, "value": 3.2, "series": [1.0, null, 3.2]},
                     {"label": "roc1", "available": true, "value": 0.4, "series": [0.1, -0.2, 0.4]}]},
                  {"symbol": "SYNB", "available": true, "asOf": "2026-10-30", "lastCompletedClose": 20,
                   "values": [
                     {"label": "roc231", "available": false, "error": "insufficient history"},
                     {"label": "roc1", "available": true, "value": 0.1, "series": [0.1]}]},
                  {"symbol": "SYNC", "available": false, "error": "no data for SYNC"}
                ], "requested": 4, "returned": 2, "available": true}
                """));

        var out = client.fetch(List.of("SYNA", "SYNB", "SYNC", "SYND"), 231, 250, 420);

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        verify(agora).callTool(eq("get_indicators_batch"), args.capture());
        JsonNode a = args.getValue();
        assertThat(a.path("symbols")).hasSize(4);
        assertThat(a.path("series").asInt()).isEqualTo(250);
        assertThat(a.path("fetchDays").asInt()).isEqualTo(420);
        assertThat(a.path("indicators").get(0).toString())
                .isEqualTo("{\"name\":\"roc\",\"params\":{\"period\":231},\"label\":\"roc231\"}");
        assertThat(a.path("indicators").get(1).toString())
                .isEqualTo("{\"name\":\"roc\",\"params\":{\"period\":1},\"label\":\"roc1\"}");

        var a1 = out.get("SYNA");
        assertThat(a1.status()).isEqualTo(MomentumBarsClient.Status.OK);
        assertThat(a1.lastClose()).isEqualByComparingTo("51.5");
        assertThat(a1.asOf()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(a1.rocLong()).hasSize(3);
        assertThat(a1.rocLong().get(1)).isNull();
        assertThat(a1.rocLong().get(2)).isEqualByComparingTo("3.2");
        assertThat(out.get("SYNB").status()).isEqualTo(MomentumBarsClient.Status.INSUFFICIENT);
        assertThat(out.get("SYNC").status()).isEqualTo(MomentumBarsClient.Status.NO_DATA);
        assertThat(out.get("SYND").status()).isEqualTo(MomentumBarsClient.Status.NO_DATA);
        assertThat(out.keySet()).containsExactly("SYNA", "SYNB", "SYNC", "SYND");
        assertThat(out.get("SYNA").roc1().get(1)).isEqualByComparingTo(new BigDecimal("-0.2"));
    }
}
