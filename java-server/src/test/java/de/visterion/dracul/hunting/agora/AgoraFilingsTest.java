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

class AgoraFilingsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void recentForm4MapsRowsAndSkipsNullDate() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenReturn(json(
                "{\"transactions\":[" +
                "{\"ticker\":\"acme\",\"filerName\":\"Jane Doe\",\"filerRole\":\"CFO\"," +
                "\"transactionDate\":\"2026-06-30\",\"shares\":1500,\"dollarValue\":45000.50,\"code\":\"P\"}," +
                "{\"ticker\":\"NODT\",\"filerName\":\"X\",\"filerRole\":\"\",\"transactionDate\":null," +
                "\"shares\":10,\"dollarValue\":100,\"code\":\"S\"}]}"));
        AgoraFilings filings = new AgoraFilings(client);

        DataSourceResult<Form4Filing> r =
                filings.recentForm4(LocalDate.parse("2026-06-24"), LocalDate.parse("2026-07-01"));
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().source()).isEqualTo("agora");
        assertThat(r.items()).hasSize(1);                    // null-date row skipped
        Form4Filing f = r.items().get(0);
        assertThat(f.ticker()).isEqualTo("ACME");            // uppercased
        assertThat(f.filerName()).isEqualTo("Jane Doe");
        assertThat(f.filerRole()).isEqualTo("CFO");
        assertThat(f.transactionDate()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(f.sharesAcquired()).isEqualByComparingTo("1500");
        assertThat(f.dollarValue()).isEqualByComparingTo("45000.50");
        assertThat(f.transactionCode()).isEqualTo("P");
    }

    @Test void recentForm4SendsWindowArgs() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any())).thenReturn(json("{\"transactions\":[]}"));
        new AgoraFilings(client).recentForm4(LocalDate.parse("2026-06-24"), LocalDate.parse("2026-07-01"));
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_form4_transactions"), args.capture());
        assertThat(args.getValue().path("from").asString()).isEqualTo("2026-06-24");
        assertThat(args.getValue().path("to").asString()).isEqualTo("2026-07-01");
    }

    @Test void recentForm4UnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_transactions"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        DataSourceResult<Form4Filing> r =
                new AgoraFilings(client).recentForm4(LocalDate.now().minusDays(7), LocalDate.now());
        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
        assertThat(r.health().source()).isEqualTo("agora");
    }

    @Test void searchSpinoffsSendsFormAndMapsRenamedFields() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json(
                "{\"filings\":[" +
                "{\"ticker\":\"spn\",\"company\":\"Acme Spinco Inc\",\"form\":\"10-12B\"," +
                "\"filedDate\":\"2026-06-20\",\"accession\":\"0001-26-000001\",\"url\":\"http://sec/u1\"}," +
                "{\"ticker\":\"\",\"company\":\"NoTicker Co\",\"form\":\"10-12B\"," +
                "\"filedDate\":\"2026-06-21\",\"accession\":\"a2\",\"url\":\"http://sec/u2\"}," +
                "{\"ticker\":\"\",\"company\":\"\",\"form\":\"10-12B\"," +
                "\"filedDate\":\"2026-06-22\",\"accession\":\"a3\",\"url\":\"http://sec/u3\"}," +
                "{\"ticker\":\"BAD\",\"company\":\"BadDate Co\",\"form\":\"10-12B\"," +
                "\"filedDate\":null,\"accession\":\"a4\",\"url\":\"http://sec/u4\"}]}"));
        AgoraFilings filings = new AgoraFilings(client);

        DataSourceResult<SpinoffFiling> r =
                filings.searchSpinoffs(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-07-01"));
        // empty-ticker+empty-company row and null-filedDate row skipped; ticker-less row kept
        assertThat(r.items()).extracting(SpinoffFiling::companyName)
                .containsExactly("Acme Spinco Inc", "NoTicker Co");
        SpinoffFiling first = r.items().get(0);
        assertThat(first.ticker()).isEqualTo("SPN");
        assertThat(first.formType()).isEqualTo("10-12B");
        assertThat(first.filingDate()).isEqualTo(LocalDate.parse("2026-06-20"));
        assertThat(first.filingUrl()).isEqualTo("http://sec/u1");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("search_filings"), args.capture());
        assertThat(args.getValue().path("forms").size()).isEqualTo(1);
        assertThat(args.getValue().path("forms").get(0).asString()).isEqualTo("10-12B");
        assertThat(args.getValue().path("from").asString()).isEqualTo("2026-05-01");
        assertThat(args.getValue().path("to").asString()).isEqualTo("2026-07-01");
    }

    @Test void searchSpinoffsUnavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenThrow(new AgoraUnavailableException("down"));
        DataSourceResult<SpinoffFiling> r =
                new AgoraFilings(client).searchSpinoffs(LocalDate.now().minusDays(60), LocalDate.now());
        assertThat(r.items()).isEmpty();
        assertThat(r.health().isHealthy()).isFalse();
    }

    @Test void searchMergersSendsBothForms() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("search_filings"), any())).thenReturn(json(
                "{\"filings\":[{\"ticker\":\"TGT\",\"company\":\"Target Co\",\"form\":\"DEFM14A\"," +
                "\"filedDate\":\"2026-06-15\",\"accession\":\"a1\",\"url\":\"http://sec/m1\"}]}"));
        AgoraFilings filings = new AgoraFilings(client);

        DataSourceResult<MergerFiling> r =
                filings.searchMergers(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-07-01"));
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).ticker()).isEqualTo("TGT");
        assertThat(r.items().get(0).formType()).isEqualTo("DEFM14A");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("search_filings"), args.capture());
        assertThat(args.getValue().path("forms").get(0).asString()).isEqualTo("DEFM14A");
        assertThat(args.getValue().path("forms").get(1).asString()).isEqualTo("SC TO-T");
    }

    @Test void conceptMapsDurationAndInstantPoints() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_concept"), any())).thenReturn(json(
                "{\"cik\":\"0000320193\",\"taxonomy\":\"us-gaap\",\"tag\":\"Assets\",\"unit\":\"USD\"," +
                "\"datapoints\":[" +
                "{\"periodStart\":\"2025-01-01\",\"periodEnd\":\"2025-12-31\",\"value\":1000.5," +
                "\"fiscalYear\":2025,\"fiscalPeriod\":\"FY\",\"form\":\"10-K\",\"filed\":\"2026-02-01\"}," +
                "{\"periodStart\":null,\"periodEnd\":\"2025-12-31\",\"value\":50000," +
                "\"fiscalYear\":2025,\"fiscalPeriod\":\"FY\",\"form\":\"10-K\",\"filed\":\"2026-02-01\"}," +
                "{\"periodStart\":null,\"periodEnd\":\"2025-12-31\",\"value\":null," +
                "\"fiscalYear\":null,\"fiscalPeriod\":\"FY\",\"form\":\"10-K\",\"filed\":null}]}"));
        AgoraFilings filings = new AgoraFilings(client);

        ConceptSeries s = filings.concept("AAPL", "Assets");
        assertThat(s.tag()).isEqualTo("Assets");
        assertThat(s.points()).hasSize(2);                    // null-value row skipped
        assertThat(s.points().get(0).periodStart()).isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(s.points().get(0).value()).isEqualByComparingTo("1000.5");
        assertThat(s.points().get(1).periodStart()).isNull(); // instant fact
        assertThat(s.points().get(1).value()).isEqualByComparingTo("50000");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_company_concept"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("AAPL");
        assertThat(args.getValue().path("tag").asString()).isEqualTo("Assets");
    }

    @Test void conceptReturnsEmptySeriesOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_concept"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraFilings(client).concept("AAPL", "Assets").isEmpty()).isTrue();
    }

    @Test void conceptStrictPropagatesAgoraFailureForBatchGuards() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_concept"), any())).thenThrow(new AgoraUnavailableException("down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AgoraFilings(client).conceptStrict("AAPL", "Assets"))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    @Test void epsHistoryMapsRows() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_eps_history"), any())).thenReturn(json(
                "{\"eps\":[{\"periodEnd\":\"2026-03-31\",\"periodStart\":\"2026-01-01\",\"value\":2.40," +
                "\"fiscalYear\":2026,\"fiscalPeriod\":\"Q1\",\"form\":\"10-Q\",\"filed\":\"2026-04-25\"}]}"));
        AgoraFilings filings = new AgoraFilings(client);

        ConceptSeries s = filings.epsHistory("AAPL");
        assertThat(s.points()).hasSize(1);
        assertThat(s.points().get(0).periodEnd()).isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(s.points().get(0).value()).isEqualByComparingTo("2.40");
    }

    @Test void epsHistoryReturnsEmptySeriesOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_eps_history"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(new AgoraFilings(client).epsHistory("AAPL").isEmpty()).isTrue();
    }

    @Test void ownerHistoryMapsOwnersTransactionsTriStateAndTruncated() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_owner_history"), any())).thenReturn(json(
                "{\"cik\":\"0000320193\",\"from\":\"2023-07-12\",\"to\":\"2026-07-12\",\"owners\":[" +
                "{\"name\":\"Jane Doe\",\"cik\":\"0001111\",\"role\":\"Chief Executive Officer\"," +
                "\"transactions\":[" +
                "{\"transactionDate\":\"2026-06-30\",\"code\":\"P\",\"acquiredDisposedCode\":\"A\",\"form\":\"4\"," +
                "\"shares\":1500,\"price\":12.50,\"dollarValue\":18750,\"sharesOwnedFollowing\":9000,\"aff10b5One\":true}," +
                "{\"transactionDate\":\"2025-06-20\",\"code\":\"P\",\"acquiredDisposedCode\":\"A\",\"form\":\"4\"," +
                "\"shares\":1000,\"price\":null,\"dollarValue\":null,\"sharesOwnedFollowing\":null,\"aff10b5One\":false}," +
                "{\"transactionDate\":\"2024-06-10\",\"code\":\"P\",\"acquiredDisposedCode\":\"A\",\"form\":\"4\"," +
                "\"shares\":800,\"dollarValue\":9600,\"aff10b5One\":null}]}," +
                "{\"name\":\"John Roe\",\"cik\":\"\",\"role\":\"\",\"transactions\":[]}]," +
                "\"truncated\":true}"));
        AgoraFilings filings = new AgoraFilings(client);

        Form4OwnerHistory h = filings.ownerHistoryStrict("AAPL");

        assertThat(h.cik()).isEqualTo("0000320193");
        assertThat(h.from()).isEqualTo(LocalDate.parse("2023-07-12"));
        assertThat(h.truncated()).isTrue();
        assertThat(h.owners()).hasSize(2);

        var jane = h.owners().get(0);
        assertThat(jane.name()).isEqualTo("Jane Doe");
        assertThat(jane.cik()).isEqualTo("0001111");
        assertThat(jane.role()).isEqualTo("Chief Executive Officer");
        assertThat(jane.transactions()).hasSize(3);

        var t0 = jane.transactions().get(0);
        assertThat(t0.transactionDate()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(t0.code()).isEqualTo("P");
        assertThat(t0.price()).isEqualByComparingTo("12.50");
        assertThat(t0.sharesOwnedFollowing()).isEqualByComparingTo("9000");
        assertThat(t0.aff10b5One()).isTrue();                       // explicit true

        var t1 = jane.transactions().get(1);
        assertThat(t1.price()).isNull();                            // null preserved (not zero)
        assertThat(t1.sharesOwnedFollowing()).isNull();
        assertThat(t1.aff10b5One()).isFalse();                      // explicit false

        var t2 = jane.transactions().get(2);
        assertThat(t2.aff10b5One()).isNull();                       // tri-state unknown (pre-2023)
        assertThat(t2.price()).isNull();                            // absent -> null

        assertThat(h.owners().get(1).transactions()).isEmpty();

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_form4_owner_history"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("AAPL");
    }

    @Test void ownerHistorySkipsMalformedTransactionKeepingTheRest() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_owner_history"), any())).thenReturn(json(
                "{\"cik\":\"0000320193\",\"owners\":[" +
                "{\"name\":\"Jane Doe\",\"cik\":\"0001111\",\"role\":\"CEO\",\"transactions\":[" +
                "{\"transactionDate\":\"not-a-date\",\"code\":\"P\",\"shares\":100}," +   // unparseable date
                "{\"transactionDate\":\"2026-06-30\",\"code\":\"P\",\"shares\":200}]}]," +
                "\"truncated\":false}"));
        AgoraFilings filings = new AgoraFilings(client);

        Form4OwnerHistory h = filings.ownerHistoryStrict("AAPL");

        // the whole owner history survives; only the one broken-date transaction is dropped
        assertThat(h.owners()).hasSize(1);
        assertThat(h.owners().get(0).transactions()).hasSize(1);
        assertThat(h.owners().get(0).transactions().get(0).transactionDate())
                .isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(h.owners().get(0).transactions().get(0).shares()).isEqualByComparingTo("200");
    }

    @Test void ownerHistoryPropagatesAgoraFailureForBatchGuards() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_form4_owner_history"), any())).thenThrow(new AgoraUnavailableException("down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AgoraFilings(client).ownerHistoryStrict("AAPL"))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
