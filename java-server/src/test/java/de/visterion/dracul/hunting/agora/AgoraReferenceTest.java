package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraReferenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void constituentsMapsRowsAndSkipsNullDateAdded() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_index_constituents"), any())).thenReturn(json(
                "{\"index\":\"sp500\",\"constituents\":[" +
                "{\"symbol\":\"NEWO\",\"name\":\"NewCo Inc\",\"sector\":\"Industrials\",\"dateAdded\":\"2026-06-20\"}," +
                "{\"symbol\":\"NODT\",\"name\":\"NoDate Co\",\"sector\":null,\"dateAdded\":null}," +
                "{\"symbol\":\"\",\"name\":\"NoSym Co\",\"sector\":null,\"dateAdded\":\"2026-06-21\"}]}"));
        AgoraReference reference = new AgoraReference(client);

        DataSourceResult<Sp500Constituent> r = reference.constituents();
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().source()).isEqualTo("agora");
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).symbol()).isEqualTo("NEWO");
        assertThat(r.items().get(0).companyName()).isEqualTo("NewCo Inc");
        assertThat(r.items().get(0).dateAdded()).isEqualTo(LocalDate.parse("2026-06-20"));

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_index_constituents"), args.capture());
        assertThat(args.getValue().path("index").asString()).isEqualTo("sp500");
    }

    @Test void constituentsUnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_index_constituents"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        DataSourceResult<Sp500Constituent> r = new AgoraReference(client).constituents();
        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().source()).isEqualTo("agora");
    }
}
