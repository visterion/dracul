package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.news.NewsCredibilityProperties;
import de.visterion.dracul.hunting.news.NewsCredibilityScorer;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3: recommendations() gets a 60min positive-only cache; recommendationsStrict() must stay
 * uncached so the Insider/Lazarus enrichment outage guard keeps working.
 */
class AgoraCompanyDataAnalystCacheTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    /** News path is unused in this test class — an empty table is fine. */
    private static AgoraCompanyData data(AgoraClient client, boolean includeSocial) {
        NewsCredibilityProperties props = new NewsCredibilityProperties(0.5, 0.3, List.of());
        return new AgoraCompanyData(client, includeSocial, new NewsCredibilityScorer(props), props);
    }

    @Test void recommendationsCachesSuccessButStrictStillThrowsDuringOutage() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenReturn(json(
                "{\"symbol\":\"STT\",\"recommendations\":[" +
                "{\"period\":\"2026-07\",\"strongBuy\":3,\"buy\":2,\"hold\":1,\"sell\":0,\"strongSell\":0}]}"));
        AgoraCompanyData data = data(client, false);

        // Warm the cache via the swallowing entry point.
        List<RecommendationTrend> first = data.recommendations("STT");
        assertThat(first).hasSize(1);

        // Agora goes down.
        when(client.callTool(eq("get_analyst_estimates"), any()))
                .thenThrow(new AgoraUnavailableException("down"));

        // Strict variant must still propagate the outage — the cache must not intercept it.
        assertThatThrownBy(() -> data.recommendationsStrict("STT"))
                .isInstanceOf(AgoraUnavailableException.class);

        // Swallowing variant returns the cached (stale) value instead of throwing.
        List<RecommendationTrend> cached = data.recommendations("STT");
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).period()).isEqualTo("2026-07");
    }

    @Test void recommendationsDoesNotCacheOutages() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        AgoraCompanyData data = data(client, false);

        assertThat(data.recommendations("STT")).isEmpty();
        assertThat(data.recommendations("STT")).isEmpty();

        verify(client, times(2)).callTool(eq("get_analyst_estimates"), any());
    }

    @Test void recommendationsCachesSuccessAndCallsAgoraOnlyOnce() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenReturn(json(
                "{\"symbol\":\"STT\",\"recommendations\":[" +
                "{\"period\":\"2026-07\",\"strongBuy\":3,\"buy\":2,\"hold\":1,\"sell\":0,\"strongSell\":0}]}"));
        AgoraCompanyData data = data(client, false);

        assertThat(data.recommendations("STT")).hasSize(1);
        assertThat(data.recommendations("STT")).hasSize(1);

        verify(client, times(1)).callTool(eq("get_analyst_estimates"), any());
    }
}
