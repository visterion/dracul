package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BenchmarkImportControllerTest {

    private static final String TOKEN = "test-import-token";
    private final ScreenCandidateRecorder recorder = mock(ScreenCandidateRecorder.class);
    private final JsonMapper json = new JsonMapper();

    private ResponseEntity<Object> call(String token, String auth, String body) {
        return new BenchmarkImportController(token, recorder).importCandidates(auth, json.readTree(body));
    }

    @Test
    void blankTokenAnswers404EvenWithAHeader() {
        assertThat(call("", "Bearer anything", "[]").getStatusCode().value()).isEqualTo(404);
        assertThat(call("  ", null, "[]").getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(recorder);
    }

    @Test
    void wrongOrMissingBearerAnswers401() {
        assertThat(call(TOKEN, "Bearer wrong", "[]").getStatusCode().value()).isEqualTo(401);
        assertThat(call(TOKEN, null, "[]").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(recorder);
    }

    @Test
    void unknownAgentAnswers400AndStoresNothing() {
        ResponseEntity<Object> resp = call(TOKEN, "Bearer " + TOKEN, """
                [{"agent":"strigoi-merger","fetched_at":"2026-07-14T08:00:00Z","items":[]},
                 {"agent":"strigoi-tech","fetched_at":"2026-07-14T08:00:00Z","items":[]}]""");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(recorder, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void malformedGroupsAnswer400() {
        String auth = "Bearer " + TOKEN;
        assertThat(call(TOKEN, auth, "{\"agent\":\"strigoi-merger\"}").getStatusCode().value()).isEqualTo(400);
        assertThat(call(TOKEN, auth, """
                [{"agent":"strigoi-merger","fetched_at":"yesterday","items":[]}]""").getStatusCode().value())
                .isEqualTo(400);
        assertThat(call(TOKEN, auth, """
                [{"agent":"strigoi-merger","fetched_at":"2026-07-14T08:00:00Z"}]""").getStatusCode().value())
                .isEqualTo(400);
        verifyNoInteractions(recorder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validGroupsAreStoredAsBackfillAndTheCountsSummed() {
        when(recorder.store(any(), any(), any(), any(), eq("BACKFILL"))).thenReturn(new CaptureCounts(2, 1, 1));

        ResponseEntity<Object> resp = call(TOKEN, "Bearer " + TOKEN, """
                [{"agent":"strigoi-merger","fetched_at":"2026-07-14T08:00:44.803367Z",
                  "items":[{"symbol":"SYNA"},{"symbol":"","companyName":"Synthetic Corp (SYNB)"}]},
                 {"agent":"strigoi-insider","fetched_at":"2026-07-15T21:00:00Z","items":[{"ticker":"SYNC"}]}]""");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) resp.getBody())
                .containsEntry("groups", 2).containsEntry("stored", 4)
                .containsEntry("duplicate", 2).containsEntry("symbolLess", 2);
        verify(recorder).store(eq("strigoi-merger"), eq(Instant.parse("2026-07-14T08:00:44.803367Z")),
                same(ScreenItemMappers.MERGER), argThat(l -> l.size() == 2), eq("BACKFILL"));
        verify(recorder).store(eq("strigoi-insider"), eq(Instant.parse("2026-07-15T21:00:00Z")),
                same(ScreenItemMappers.INSIDER), argThat(l -> l.size() == 1), eq("BACKFILL"));
    }
}
