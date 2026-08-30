package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP layer, which {@code DepotEquityBackfillIT} never exercises (it calls
 * {@code service.run} directly, the established pattern in this package -- see
 * {@code DepotEquitySnapshotRepositoryIT}). Proves the URL template, the {@code
 * @PathVariable} binding, and both error mappings, with the service mocked out.
 */
class DepotEquityBackfillControllerTest {

    private final DepotEquityBackfillService service = mock(DepotEquityBackfillService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new DepotEquityBackfillController(service))
            .build();

    @Test
    void aConnectionWithNoPositionsAnswers200WithAnEmptyReport() throws Exception {
        var empty = new DepotEquityBackfillService.BackfillReport(
                "c1", null, null, 0, 0, 0, 0, 0,
                java.util.List.of(), java.util.List.of(), null, null);
        when(service.run("c1")).thenReturn(empty);

        mvc.perform(post("/api/depots/c1/equity/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection").value("c1"))
                .andExpect(jsonPath("$.daysInserted").value(0));
    }

    @Test
    void aConflictAnswers409() throws Exception {
        when(service.run(eq("c1"))).thenThrow(
                new DepotEquityBackfillService.BackfillConflictException("no measured DAILY row for c1"));

        mvc.perform(post("/api/depots/c1/equity/backfill"))
                .andExpect(status().isConflict());
    }

    @Test
    void anAgoraOutageAnswers503WithoutLeakingTheRawMessage() throws Exception {
        when(service.run(eq("c1"))).thenThrow(
                new AgoraUnavailableException("Agora unreachable for get_ohlc: connect timed out to internal-host:9999"));

        mvc.perform(post("/api/depots/c1/equity/backfill"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("internal-host");
                });
    }

    @Test
    void aDepotOutageAlsoAnswers503() throws Exception {
        when(service.run(eq("c1"))).thenThrow(new DepotUnavailableException("agora depot call failed: get_positions"));

        mvc.perform(post("/api/depots/c1/equity/backfill"))
                .andExpect(status().isServiceUnavailable());
    }
}
