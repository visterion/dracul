package de.visterion.dracul.watchlist;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.AgoraClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Task 8: end-to-end HTTP tests for the watchlist create endpoint against a real Postgres
 * (same pattern as {@code WatchlistControllerIT}), with only {@link AgoraClient} mocked out —
 * the point of these tests is that a real {@link WatchlistRepository} runs the real SQL, so the
 * exact {@code company_name = ticker} backfill condition is actually exercised, not merely
 * asserted against a mock's canned return value.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistControllerTest {

    private static final String USER = "default";

    /** Exactly 24 chars — the widened pattern's upper boundary; must still be accepted. */
    private static final String SYMBOL_24_CHARS = "A" + "B".repeat(23);
    /** Exactly 25 chars — one past the boundary; must be rejected. */
    private static final String SYMBOL_25_CHARS = "A" + "B".repeat(24);

    @LocalServerPort int port;

    @Autowired JsonMapper objectMapper;
    @Autowired WatchlistRepository repo;
    @Autowired JdbcClient jdbc;

    @MockitoBean AgoraClient agora;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
        CurrentUserHolder.set(USER);
        cleanupTestRows();
        stubAgora();
    }

    @AfterEach
    void tearDown() {
        cleanupTestRows();
        CurrentUserHolder.clear();
    }

    private void cleanupTestRows() {
        jdbc.sql("DELETE FROM watchlist_items WHERE user_id = :user AND ticker IN "
                        + "('SYNA','SYNB','SYNC','SYND','SYNE','SYNF','AT0000A324Q2.VI','NOKIA',"
                        + ":sym24)")
                .param("user", USER)
                .param("sym24", SYMBOL_24_CHARS)
                .update();
    }

    /** get_quote resolves every symbol except NOKIA (Agora's real noData shape, measured on
     *  prod 2026-08-08); get_ohlc always answers with an empty bar history. */
    private void stubAgora() {
        when(agora.callTool(eq("get_quote"), any())).thenAnswer(inv -> {
            JsonNode args = inv.getArgument(1);
            String symbol = args.path("symbols").get(0).asString("");
            if ("NOKIA".equals(symbol)) {
                return objectMapper.readTree("""
                        {"quotes":[],"unresolved":["NOKIA"],"available":false,"error":"no quote for NOKIA"}
                        """);
            }
            return objectMapper.readTree("""
                    {"quotes":[{"symbol":"%s","price":10.0,"dayChangePercent":0.5,"currency":"USD"}]}
                    """.formatted(symbol));
        });
        when(agora.callTool(eq("get_ohlc"), any()))
                .thenReturn(objectMapper.readTree("{\"bars\":[]}"));
    }

    private ResponseEntity<JsonNode> post(String jsonBody) {
        return rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.readTree(jsonBody))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(JsonNode.class);
    }

    @Test void addsAFifteenCharSymbol() {
        // AT0000A324Q2.VI is 15 chars; the old 12-char pattern rejected it with 400.
        var response = post("""
                {"symbol":"AT0000A324Q2.VI","tag":"TRACKING"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test void unknownSymbolIsUnprocessableNotBadGateway() {
        // Agora answers noData -> empty quotes -> MarketDataException.NOT_FOUND -> 422.
        var response = post("""
                {"symbol":"NOKIA","tag":"TRACKING"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test void theSuppliedNameWinsOverTheSymbolFallback() {
        var response = post("""
                {"symbol":"SYNA","tag":"TRACKING","name":"Synthetic Alpha Oyj"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().get("companyName").asString()).isEqualTo("Synthetic Alpha Oyj");
    }

    @Test void anOverlongNameIsRejected() {
        var response = post("{\"symbol\":\"SYNA\",\"tag\":\"TRACKING\",\"name\":\""
                + "x".repeat(129) + "\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test void reAddingBackfillsATickerNamedRowAndTheResponseAlreadyShowsIt() {
        // Row exists with company_name == ticker; re-adding with a name must fill it in AND
        // the 200 body must carry the new name (the backfill has to run BEFORE findById).
        repo.insert(USER, "SYNA", "SYNA", 5.0, List.of(5.0), "TRACKING", "manual", null, "EUR");

        var response = post("""
                {"symbol":"SYNA","tag":"TRACKING","name":"Synthetic Alpha Oyj"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("companyName").asString()).isEqualTo("Synthetic Alpha Oyj");
    }

    @Test void reAddingDoesNotOverwriteARealName() {
        // Row exists with a genuine company name -> stays untouched.
        repo.insert(USER, "SYNB", "Synthetic Beta AG", 5.0, List.of(5.0), "TRACKING", "manual", null, "EUR");

        var response = post("""
                {"symbol":"SYNB","tag":"TRACKING","name":"Something Else GmbH"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("companyName").asString()).isEqualTo("Synthetic Beta AG");
    }

    /**
     * Fix round 1: the {@code company_name = ticker} comparison must be exact — no case
     * folding, no trimming. A row named {@code 'sync'} against ticker {@code SYNC}, or
     * {@code ' SYND '} against ticker {@code SYND}, does NOT satisfy an exact match and must
     * stay untouched. A {@code lower()} or {@code trim()} variant of the SQL would heal both
     * rows here and fail this test.
     */
    @Test void reAddingDoesNotOverwriteALooseTickerMatch() {
        repo.insert(USER, "SYNC", "sync", 5.0, List.of(5.0), "TRACKING", "manual", null, "EUR");
        repo.insert(USER, "SYND", " SYND ", 5.0, List.of(5.0), "TRACKING", "manual", null, "EUR");

        var caseResponse = post("""
                {"symbol":"SYNC","tag":"TRACKING","name":"Synthetic Gamma Oyj"}
                """);
        var whitespaceResponse = post("""
                {"symbol":"SYND","tag":"TRACKING","name":"Synthetic Delta Oyj"}
                """);

        assertThat(caseResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(caseResponse.getBody().get("companyName").asString()).isEqualTo("sync");
        assertThat(whitespaceResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(whitespaceResponse.getBody().get("companyName").asString()).isEqualTo(" SYND ");
    }

    @Test void aTwentyFourCharSymbolIsAcceptedAndTwentyFiveIsRejected() {
        var accepted = post("{\"symbol\":\"" + SYMBOL_24_CHARS + "\",\"tag\":\"TRACKING\"}");
        var rejected = post("{\"symbol\":\"" + SYMBOL_25_CHARS + "\",\"tag\":\"TRACKING\"}");

        assertThat(accepted.getStatusCode().value()).isEqualTo(201);
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
    }

    @Test void blankNameFallsBackToTheSymbolAndAPaddedNameIsStoredTrimmed() {
        var blank = post("""
                {"symbol":"SYNE","tag":"TRACKING","name":""}
                """);
        var padded = post("""
                {"symbol":"SYNF","tag":"TRACKING","name":"  Trimmed Name  "}
                """);

        assertThat(blank.getStatusCode().value()).isEqualTo(201);
        assertThat(blank.getBody().get("companyName").asString()).isEqualTo("SYNE");
        assertThat(padded.getStatusCode().value()).isEqualTo(201);
        assertThat(padded.getBody().get("companyName").asString()).isEqualTo("Trimmed Name");
    }
}
