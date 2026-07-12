package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.lazarus.FundamentalScore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraFilingsFundamentalScoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void fundamentalScoreMapsPiotroskiF() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_score"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\"," +
                "\"scores\":{\"piotroskiF\":{" +
                "\"score\":7,\"criteriaAvailable\":8," +
                "\"criteria\":{\"cfoExceedsNetIncome\":{\"met\":true,\"available\":true}," +
                "\"roaPositive\":{\"met\":true,\"available\":true}}," +
                "\"raw\":{\"accrualRatio\":-0.03,\"roa\":0.12}}}}"));
        AgoraFilings filings = new AgoraFilings(client);

        FundamentalScore s = filings.fundamentalScore("AAPL");
        assertThat(s.score()).isEqualTo(7);
        assertThat(s.criteriaAvailable()).isEqualTo(8);
        assertThat(s.accrualRatio()).isEqualByComparingTo("-0.03");
        assertThat(s.cfoExceedsNetIncome()).isTrue();
        assertThat(s.cfoExceedsNetIncomeAvailable()).isTrue();
        assertThat(s.available()).isTrue();
    }

    @Test void fundamentalScoreUnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_score"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        FundamentalScore s = new AgoraFilings(client).fundamentalScore("AAPL");
        assertThat(s.available()).isFalse();
    }

    @Test void fundamentalScoreStrictPropagatesAgoraFailureForBatchGuards() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_score"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AgoraFilings(client).fundamentalScoreStrict("AAPL"))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    @Test void fundamentalScoreUnavailableWhenPiotroskiFMissing() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_score"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"scores\":{}}"));
        FundamentalScore s = new AgoraFilings(client).fundamentalScore("AAPL");
        assertThat(s.available()).isFalse();
    }
}
