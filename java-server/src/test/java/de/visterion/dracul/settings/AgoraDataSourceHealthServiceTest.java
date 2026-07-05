package de.visterion.dracul.settings;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraDataSourceHealthServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void pingOkYieldsSingleOkAgoraRow() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("ping"), any())).thenReturn(mapper.readTree("{\"pong\":true}"));
        var svc = new AgoraDataSourceHealthService(agora);
        List<DataSourceHealth> rows = svc.probeAll(true);
        assertThat(rows).hasSize(1);
        DataSourceHealth r = rows.get(0);
        assertThat(r.id()).isEqualTo("agora");
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.configured()).isTrue();
        assertThat(r.latencyMs()).isNotNull();
        assertThat(r.usedBy()).contains("quotes", "ohlc", "fx");
    }

    @Test void agoraUnavailableYieldsSingleErrorRow() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("ping"), any())).thenThrow(new AgoraUnavailableException("connection refused"));
        var svc = new AgoraDataSourceHealthService(agora);
        List<DataSourceHealth> rows = svc.probeAll(true);
        assertThat(rows).hasSize(1);
        DataSourceHealth r = rows.get(0);
        assertThat(r.id()).isEqualTo("agora");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.httpStatus()).isNull();
        assertThat(r.detail()).contains("connection refused");
    }

    @Test void resultsAreCachedWithinTtlUnlessRefresh() {
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(agora.callTool(eq("ping"), any())).thenReturn(mapper.readTree("{\"pong\":true}"));
        var svc = new AgoraDataSourceHealthService(agora);
        String firstCheckedAt = svc.probeAll(true).get(0).checkedAt();
        String cachedCheckedAt = svc.probeAll(false).get(0).checkedAt();
        assertThat(cachedCheckedAt).isEqualTo(firstCheckedAt); // cache hit → same timestamp
        String refreshedCheckedAt = svc.probeAll(true).get(0).checkedAt();
        assertThat(refreshedCheckedAt).isNotEqualTo(firstCheckedAt); // forced refresh → new
    }
}
