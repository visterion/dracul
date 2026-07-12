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

    @Test void indexChangesMapsRowsUppercasesSymbolAndSkipsMalformed() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_index_constituent_changes"), any())).thenReturn(json(
                "{\"index\":\"sp500\",\"changes\":[" +
                "{\"symbol\":\"newo\",\"action\":\"add\",\"index\":\"sp500\"," +
                "  \"announcementDate\":\"2026-06-18\",\"effectiveDate\":\"2026-06-24\",\"source\":\"sp_press\"}," +
                "{\"symbol\":\"NOEFF\",\"action\":\"remove\",\"index\":\"sp500\"," +
                "  \"announcementDate\":\"2026-06-18\",\"effectiveDate\":null,\"source\":\"sp_press\"}," +
                "{\"symbol\":\"\",\"action\":\"add\",\"index\":\"sp500\"," +
                "  \"announcementDate\":\"2026-06-18\",\"effectiveDate\":\"2026-06-24\",\"source\":\"sp_press\"}," +
                "{\"symbol\":\"NOANN\",\"action\":\"add\",\"index\":\"sp500\"," +
                "  \"effectiveDate\":\"2026-06-25\",\"source\":\"russell_reconstitution\"}]}"));
        AgoraReference reference = new AgoraReference(client);

        DataSourceResult<IndexChangeEvent> r = reference.indexChanges("sp500", 30);
        assertThat(r.health().isHealthy()).isTrue();
        // newo mapped (uppercased); NOEFF dropped (null effectiveDate); "" dropped (blank symbol);
        // NOANN kept (missing announcementDate is lenient -> null).
        assertThat(r.items()).hasSize(2);

        IndexChangeEvent first = r.items().get(0);
        assertThat(first.symbol()).isEqualTo("NEWO");
        assertThat(first.companyName()).isEmpty();
        assertThat(first.index()).isEqualTo("sp500");
        assertThat(first.action()).isEqualTo("add");
        assertThat(first.announcementDate()).isEqualTo(LocalDate.parse("2026-06-18"));
        assertThat(first.effectiveDate()).isEqualTo(LocalDate.parse("2026-06-24"));
        assertThat(first.source()).isEqualTo("sp_press");

        IndexChangeEvent noann = r.items().get(1);
        assertThat(noann.symbol()).isEqualTo("NOANN");
        assertThat(noann.announcementDate()).isNull();

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_index_constituent_changes"), args.capture());
        assertThat(args.getValue().path("index").asString()).isEqualTo("sp500");
        assertThat(args.getValue().path("lookback_days").asInt()).isEqualTo(30);
    }

    @Test void indexChangesUnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_index_constituent_changes"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        DataSourceResult<IndexChangeEvent> r = new AgoraReference(client).indexChanges("sp500", 30);
        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().source()).isEqualTo("agora");
    }
}
