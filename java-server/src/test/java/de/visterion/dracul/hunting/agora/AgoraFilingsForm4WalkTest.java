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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * BUG-S1b: {@code recentForm4} fetches its window as a RECEDING WALK — {@code from} fixed, only
 * {@code to} stepping back — instead of one call for the whole window, and instead of the
 * day-sized slices this replaced.
 *
 * <p>Measured on prod Agora {@code 4586c51}, 2026-08-05 (limit=1000, ~35 s per call): a ONE-DAY
 * window for 2026-08-04 returned 13 transactions and zero open-market buys, while the window
 * 07-29..08-05 returned 676 transactions of which 191 were dated 2026-08-04. EFTS orders by
 * {@code file_date} descending and SEC §16(a) grants two business days, so filings filed on day D
 * mostly report trades of D-1/D-2 — which the transaction-date filter discards. Narrow windows
 * spend their whole budget on the lowest-yield filings that exist; the day-slicing was therefore
 * WORSE than the single call it replaced. Receding {@code to} while holding {@code from} keeps
 * every read filing inside the caller's window: 07-29..08-05, 07-29..08-03 and 07-29..08-01 union
 * to ~1,400 transactions against 676 for one call.
 *
 * <p>All fixtures here are SYNTHETIC (symbols SYNA/SYNB, invented filers and numbers), never a
 * captured production response.
 */
class AgoraFilingsForm4WalkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    /** The measured week: an inclusive 8-day window, exactly what lookback_days=7 produces. */
    private static final LocalDate FROM = LocalDate.parse("2026-07-29");
    private static final LocalDate TO = LocalDate.parse("2026-08-05");

    /** One synthetic open-market buy dated {@code day}. */
    private String tx(String day, String ticker, String filer, String shares, String dollars) {
        return "{\"ticker\":\"" + ticker + "\",\"filerName\":\"" + filer + "\",\"filerRole\":\"CFO\","
                + "\"transactionDate\":\"" + day + "\",\"shares\":" + shares + ",\"dollarValue\":"
                + dollars + ",\"code\":\"P\"}";
    }

    /** Answers every call with one buy dated on that call's own {@code to} (its newest day), and
     *  with {@code truncated=true} — which is what EVERY call of this walk really returns. */
    private AgoraClient oneBuyPerCall() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("to").asString();
            return json("{\"truncated\":true,\"transactions\":["
                    + tx(day, "SYNA", "Ada Synthetic", "1000", "50000") + "]}");
        });
        return client;
    }

    private List<JsonNode> capturedArgs(AgoraClient client, int expectedCalls) {
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client, Mockito.times(expectedCalls))
                .callTool(eq("get_form4_transactions"), args.capture());
        return args.getAllValues();
    }

    /** The walk itself: `from` never moves, `to` recedes by FORM4_WALK_STEP_DAYS (2), and the
     *  walk stops when receding further would precede `from`. */
    @Test void theDefaultEightDayWindowWalksToBackwardsInFourCalls() {
        AgoraClient client = oneBuyPerCall();

        new AgoraFilings(client).recentForm4(FROM, TO);

        List<JsonNode> args = capturedArgs(client, 4);
        assertThat(args).extracting(a -> a.path("from").asString() + ".." + a.path("to").asString())
                .containsExactly(
                        "2026-07-29..2026-08-05",   // the widest call, highest yield
                        "2026-07-29..2026-08-03",
                        "2026-07-29..2026-08-01",
                        "2026-07-29..2026-07-30");  // next `to` would be 07-28, before `from`
        assertThat(args).allSatisfy(a -> assertThat(a.path("limit").asInt(0)).isEqualTo(1000));
    }

    /** A window shorter than one step is a single call; nothing recedes. */
    @Test void aWindowInsideOneStepIsExactlyOneCall() {
        AgoraClient client = oneBuyPerCall();

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(TO.minusDays(1), TO);

        assertThat(capturedArgs(client, 1)).singleElement().satisfies(a -> {
            assertThat(a.path("from").asString()).isEqualTo("2026-08-04");
            assertThat(a.path("to").asString()).isEqualTo("2026-08-05");
        });
        assertThat(r.health().truncated()).isFalse();   // the walk did reach `from`
    }

    @Test void everyCallsTransactionsAppearInTheMergedResultInWalkOrder() {
        DataSourceResult<Form4Filing> r = new AgoraFilings(oneBuyPerCall()).recentForm4(FROM, TO);

        assertThat(r.items()).extracting(Form4Filing::transactionDate)
                .containsExactly(LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-03"),
                        LocalDate.parse("2026-08-01"), LocalDate.parse("2026-07-30"));
    }

    /**
     * Consecutive calls overlap ON PURPOSE (the step is smaller than a call's reach, so no day
     * falls between two calls), so the same transaction genuinely arrives more than once — the
     * nested windows are not disjoint the way the old day-slices were. The record-wide
     * {@code LinkedHashSet} is what makes that safe: a double-counted buy would add a second
     * filer row to a ticker and manufacture a cluster that never happened.
     */
    @Test void aTransactionReturnedByOverlappingCallsAppearsOnce() {
        String shared = tx("2026-07-31", "SYNA", "Ada Synthetic", "1000", "50000");
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String to = ((JsonNode) inv.getArgument(1)).path("to").asString();
            // every call re-covers 07-31; only the widest also carries a fresh 08-04 row
            String rows = "2026-08-05".equals(to)
                    ? shared + "," + tx("2026-08-04", "SYNB", "Bela Synthetic", "2000", "80000")
                    : shared;
            return json("{\"truncated\":true,\"transactions\":[" + rows + "]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.items()).hasSize(2);
        assertThat(r.items()).extracting(Form4Filing::ticker).containsExactly("SYNA", "SYNB");
    }

    /**
     * Truncation is about the WALK, not about the individual calls. Every call here comes back
     * {@code truncated=true} — that is what a deliberate cut of one's own window looks like — and
     * the merged answer is still clean, because the walk covered everything from `to` back to
     * `from`. OR-ing the per-call flags would mark every answer truncated and say nothing.
     */
    @Test void perCallTruncationDoesNotTruncateAWalkThatReachedFrom() {
        DataSourceResult<Form4Filing> r = new AgoraFilings(oneBuyPerCall()).recentForm4(FROM, TO);

        assertThat(r.health().truncated()).isFalse();
        assertThat(r.health().partial()).isFalse();
        assertThat(r.health().detail()).isNull();
        assertThat(r.health().isHealthy()).isTrue();
    }

    /** ...but a walk that ran out of slice budget before reaching `from` IS truncated. */
    @Test void aWalkStoppedByTheSliceBudgetIsTruncated() {
        AgoraClient client = oneBuyPerCall();

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO, 2);

        assertThat(capturedArgs(client, 2)).extracting(a -> a.path("to").asString())
                .containsExactly("2026-08-05", "2026-08-03");   // stopped, `from` not reached
        assertThat(r.items()).hasSize(2);
        assertThat(r.health().truncated()).isTrue();
        assertThat(r.health().detail()).contains("truncated");
        assertThat(r.health().isHealthy()).isTrue();            // degraded, not unavailable
    }

    /** Agora's own `partial` still means something and is still OR-ed across the calls. */
    @Test void onePartialCallMarksTheWholeAnswerPartial() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String to = ((JsonNode) inv.getArgument(1)).path("to").asString();
            return json("{\"partial\":" + "2026-08-01".equals(to) + ",\"transactions\":[]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.health().partial()).isTrue();
        assertThat(r.health().detail()).contains("partial");
    }

    /**
     * One dead call leaves a hole in the walk: the other calls' rows are kept and the answer is
     * marked truncated. Status stays "healthy" because the caller
     * ({@code StrigoiInsiderWebhookController} -> the hunter prompt) reads {@code unavailable} as
     * "return exactly {@code {"prey": []}}"; three good calls of candidates must not be discarded.
     */
    @Test void oneFailingCallKeepsTheRestAndMarksTruncated() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String to = ((JsonNode) inv.getArgument(1)).path("to").asString();
            if ("2026-08-01".equals(to)) throw new AgoraUnavailableException("edgar down");
            return json("{\"truncated\":true,\"transactions\":["
                    + tx(to, "SYNA", "Ada Synthetic", "1000", "50000") + "]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.items()).hasSize(3);
        assertThat(r.items()).extracting(Form4Filing::transactionDate)
                .doesNotContain(LocalDate.parse("2026-08-01"));
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().truncated()).isTrue();
    }

    /** A total upstream outage must still surface as unavailable, never as a quiet empty answer. */
    @Test void everyCallFailingIsUnavailable() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any()))
                .thenThrow(new AgoraUnavailableException("edgar down"));

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO);

        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("edgar down");
    }

    /** The caller-supplied budget: daywalker passes 1 and gets ONE wide call over its whole
     *  window — the pre-slicing shape, and the highest-yield single call available. */
    @Test void aSliceBudgetOfOneIssuesOneWideCallOverTheWholeWindow() {
        AgoraClient client = oneBuyPerCall();

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO, 1);

        assertThat(capturedArgs(client, 1)).singleElement().satisfies(a -> {
            assertThat(a.path("from").asString()).isEqualTo("2026-07-29");
            assertThat(a.path("to").asString()).isEqualTo("2026-08-05");
        });
        assertThat(r.items()).hasSize(1);
        assertThat(r.health().truncated()).isTrue();   // one call cannot cover eight days
    }

    /** A nonsensical budget may not turn the fetch into a zero-call "clean empty" answer. */
    @Test void aSliceBudgetBelowOneIsClampedToOneCall() {
        AgoraClient client = oneBuyPerCall();

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(FROM, TO, 0);

        assertThat(capturedArgs(client, 1)).hasSize(1);
        assertThat(r.items()).hasSize(1);
    }

    /** A 30-day lookback (the schema maximum) is bounded by the slice cap, not by the window. */
    @Test void theSliceCapBoundsTheWalkOnTheLongestPermittedLookback() {
        AgoraClient client = oneBuyPerCall();

        DataSourceResult<Form4Filing> r =
                new AgoraFilings(client).recentForm4(TO.minusDays(30), TO);

        capturedArgs(client, AgoraFilings.MAX_WINDOW_SLICES);
        assertThat(r.health().truncated()).isTrue();
    }
}
