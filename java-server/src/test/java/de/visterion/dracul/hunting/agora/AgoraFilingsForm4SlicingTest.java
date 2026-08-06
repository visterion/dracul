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
 * BUG-S1b: {@code recentForm4} slices its window into DAY-sized {@code get_form4_transactions}
 * calls instead of asking for the whole week at once.
 *
 * <p>Why: Agora reads one EDGAR archive document per filing under a 30 s aggregate deadline at
 * ~110 ms spacing, i.e. ~272 filings per call. A market-wide week holds ~1,697 Form-4 filings
 * (measured live 2026-08-04) and a market-wide day ~243 — so the week-sized call read 139 of them,
 * found zero tickers with three filers and the insider hunter reported {@code items=0
 * (truncated=true status=healthy)} in every production round. A day fits; a week does not.
 *
 * <p>All fixtures here are SYNTHETIC (symbols SYNA/SYNB, invented filers and numbers), never a
 * captured production response.
 */
class AgoraFilingsForm4SlicingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    private static final LocalDate MON = LocalDate.parse("2026-07-20");
    private static final LocalDate SUN = LocalDate.parse("2026-07-26");   // 7-day window [MON,SUN]

    /** One synthetic open-market buy dated {@code day}. */
    private String tx(String day, String ticker, String filer, String shares, String dollars) {
        return "{\"ticker\":\"" + ticker + "\",\"filerName\":\"" + filer + "\",\"filerRole\":\"CFO\","
                + "\"transactionDate\":\"" + day + "\",\"shares\":" + shares + ",\"dollarValue\":"
                + dollars + ",\"code\":\"P\"}";
    }

    /** Answers every slice with one buy dated on that slice's own {@code from}. */
    private AgoraClient oneBuyPerDay() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("from").asString();
            return json("{\"transactions\":[" + tx(day, "SYNA", "Ada Synthetic", "1000", "50000") + "]}");
        });
        return client;
    }

    private List<JsonNode> capturedArgs(AgoraClient client, int expectedCalls) {
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client, Mockito.times(expectedCalls))
                .callTool(eq("get_form4_transactions"), args.capture());
        return args.getAllValues();
    }

    @Test void sevenDayWindowIssuesOneCallPerDayWithThatDaysBounds() {
        AgoraClient client = oneBuyPerDay();

        new AgoraFilings(client).recentForm4(MON, SUN);

        List<JsonNode> args = capturedArgs(client, 7);
        assertThat(args).extracting(a -> a.path("from").asString() + ".." + a.path("to").asString())
                .containsExactly(
                        "2026-07-20..2026-07-20", "2026-07-21..2026-07-21", "2026-07-22..2026-07-22",
                        "2026-07-23..2026-07-23", "2026-07-24..2026-07-24", "2026-07-25..2026-07-25",
                        "2026-07-26..2026-07-26");
    }

    @Test void everySlicesTransactionsAppearInTheMergedResultInDayOrder() {
        DataSourceResult<Form4Filing> r = new AgoraFilings(oneBuyPerDay()).recentForm4(MON, SUN);

        assertThat(r.items()).extracting(Form4Filing::transactionDate)
                .containsExactly(MON, MON.plusDays(1), MON.plusDays(2), MON.plusDays(3),
                        MON.plusDays(4), MON.plusDays(5), SUN);
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().truncated()).isFalse();
        assertThat(r.health().detail()).isNull();
    }

    @Test void oneTruncatedSliceTruncatesTheWholeAnswer() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("from").asString();
            boolean cut = "2026-07-23".equals(day);          // exactly one day was cut
            return json("{\"truncated\":" + cut + ",\"transactions\":["
                    + tx(day, "SYNA", "Ada Synthetic", "1000", "50000") + "]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, SUN);

        assertThat(r.items()).hasSize(7);                    // all days kept
        assertThat(r.health().truncated()).isTrue();         // ...and the cut is NOT lost
        assertThat(r.health().detail()).contains("truncated");
        assertThat(r.health().isHealthy()).isTrue();         // degraded, not unavailable
    }

    @Test void onePartialSliceMarksTheWholeAnswerPartial() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("from").asString();
            return json("{\"partial\":" + "2026-07-21".equals(day) + ",\"transactions\":[]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, SUN);

        assertThat(r.health().partial()).isTrue();
        assertThat(r.health().detail()).contains("partial");
    }

    /**
     * The late-filing pad of slice D covers filing dates D+1..D+10, i.e. the following slices' own
     * windows. Agora narrows every slice back to the caller's window on the TRANSACTION date, so a
     * duplicate transaction should not reach us — this pins that a duplicate that DOES reach us is
     * collapsed rather than double-counted, because a second copy of a filer's buy would
     * manufacture a cluster that never happened.
     *
     * <p>Verified to fail against a naive concatenation (merged collected into an {@code ArrayList}
     * instead of a {@code LinkedHashSet}): {@code Expected size: 2 but was: 3 in:
     * [Form4Filing[ticker=SYNA, filerName=Ada Synthetic, filerRole=CFO,
     * transactionDate=2026-07-20, ...], Form4Filing[ticker=SYNA, filerName=Ada Synthetic, ...],
     * Form4Filing[ticker=SYNB, filerName=Bela Synthetic, ...]]}
     */
    @Test void aTransactionReturnedByTwoSlicesAppearsOnce() {
        String duplicated = tx("2026-07-20", "SYNA", "Ada Synthetic", "1000", "50000");
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("from").asString();
            return switch (day) {
                case "2026-07-20" -> json("{\"transactions\":[" + duplicated + "]}");
                // day 21's pad-widened search re-reported day 20's filing verbatim
                case "2026-07-21" -> json("{\"transactions\":[" + duplicated + ","
                        + tx("2026-07-21", "SYNB", "Bela Synthetic", "2000", "80000") + "]}");
                default -> json("{\"transactions\":[]}");
            };
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, SUN);

        assertThat(r.items()).hasSize(2);
        assertThat(r.items()).extracting(Form4Filing::ticker).containsExactly("SYNA", "SYNB");
    }

    /**
     * One dead day must not discard six good ones — but it is data we did NOT see, so the answer
     * is marked truncated. Status stays "healthy" because the caller
     * ({@code StrigoiInsiderWebhookController} -> the hunter prompt) reads {@code unavailable} as
     * "return exactly {@code {"prey": []}}"; six good days of candidates must not be thrown away.
     */
    @Test void oneFailingSliceKeepsTheOtherDaysAndMarksTruncated() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenAnswer(inv -> {
            String day = ((JsonNode) inv.getArgument(1)).path("from").asString();
            if ("2026-07-22".equals(day)) throw new AgoraUnavailableException("edgar down");
            return json("{\"transactions\":[" + tx(day, "SYNA", "Ada Synthetic", "1000", "50000") + "]}");
        });

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, SUN);

        assertThat(r.items()).hasSize(6);
        assertThat(r.items()).extracting(Form4Filing::transactionDate)
                .doesNotContain(LocalDate.parse("2026-07-22"));
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().truncated()).isTrue();
    }

    /** A total upstream outage must still surface as unavailable, never as a quiet empty answer. */
    @Test void everySliceFailingIsUnavailable() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any()))
                .thenThrow(new AgoraUnavailableException("edgar down"));

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, SUN);

        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("edgar down");
    }

    /**
     * The slicing made the cost linear in the lookback, and the tool's input schema permits 30
     * days — 31 sequential calls at up to 45 s would be ~1395 s inside ONE tool call. The cap
     * bounds that; the days DROPPED are the oldest, and the cut is reported.
     */
    @Test void aWindowLongerThanTheSliceCapKeepsTheNewestDaysAndReportsTruncation() {
        AgoraClient client = oneBuyPerDay();
        LocalDate from = SUN.minusDays(29);                  // 30-day lookback, the schema maximum

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(from, SUN);

        List<JsonNode> args = capturedArgs(client, AgoraFilings.MAX_WINDOW_SLICES);
        assertThat(args).extracting(a -> a.path("from").asString())
                .containsExactly("2026-07-17", "2026-07-18", "2026-07-19", "2026-07-20",
                        "2026-07-21", "2026-07-22", "2026-07-23", "2026-07-24", "2026-07-25",
                        "2026-07-26");                       // the NEWEST cap days, oldest dropped
        assertThat(r.items()).hasSize(AgoraFilings.MAX_WINDOW_SLICES);
        assertThat(r.health().truncated()).isTrue();         // days we never looked at
        assertThat(r.health().detail()).contains("truncated");
        assertThat(r.health().isHealthy()).isTrue();
    }

    /** A window AT the cap is covered completely and must not be marked truncated for that. */
    @Test void aWindowExactlyAtTheSliceCapIsNotTruncated() {
        AgoraClient client = oneBuyPerDay();
        LocalDate from = SUN.minusDays(AgoraFilings.MAX_WINDOW_SLICES - 1);

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(from, SUN);

        List<JsonNode> args = capturedArgs(client, AgoraFilings.MAX_WINDOW_SLICES);
        assertThat(args.get(0).path("from").asString()).isEqualTo(from.toString());
        assertThat(r.health().truncated()).isFalse();
        assertThat(r.health().detail()).isNull();
    }

    @Test void singleDayWindowStillIssuesExactlyOneCall() {
        AgoraClient client = oneBuyPerDay();

        DataSourceResult<Form4Filing> r = new AgoraFilings(client).recentForm4(MON, MON);

        assertThat(capturedArgs(client, 1)).singleElement()
                .satisfies(a -> {
                    assertThat(a.path("from").asString()).isEqualTo("2026-07-20");
                    assertThat(a.path("to").asString()).isEqualTo("2026-07-20");
                });
        assertThat(r.items()).hasSize(1);
    }
}
