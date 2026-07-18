package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.news.NewsCredibilityProperties;
import de.visterion.dracul.hunting.news.NewsCredibilityScorer;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraCompanyDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) { return mapper.readTree(s); }

    /** Seed-like table for chokepoint tests: one wire domain, one reddit source row, one boundary row. */
    private static NewsCredibilityProperties testProps() {
        return new NewsCredibilityProperties(0.5, 0.3, List.of(
                new NewsCredibilityProperties.SourceEntry("reuters.com", 0.9),
                new NewsCredibilityProperties.SourceEntry("reddit-stocks", 0.2),
                new NewsCredibilityProperties.SourceEntry("boundary.example", 0.3),
                new NewsCredibilityProperties.SourceEntry("", 0.1)));
    }

    private static AgoraCompanyData data(AgoraClient client, boolean includeSocial) {
        NewsCredibilityProperties props = testProps();
        return new AgoraCompanyData(client, includeSocial, new NewsCredibilityScorer(props), props);
    }

    @Test void newsMapsRowsParsesInstantAndSkipsBlankHeadline() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Apple ships thing\",\"summary\":\"A summary.\",\"source\":\"WSJ\"," +
                "\"datetime\":\"2026-06-30T14:05:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"\",\"summary\":\"no headline\",\"source\":\"X\"," +
                "\"datetime\":\"2026-06-30T15:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = data(client, false);

        List<NewsHeadline> out = data.news("AAPL", LocalDate.parse("2026-06-28"), LocalDate.parse("2026-07-01"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).headline()).isEqualTo("Apple ships thing");
        assertThat(out.get(0).summary()).isEqualTo("A summary.");
        assertThat(out.get(0).source()).isEqualTo("WSJ");
        assertThat(out.get(0).datetime()).isEqualTo(Instant.parse("2026-06-30T14:05:00Z"));
        assertThat(out.get(0).url()).isEqualTo("http://n/1");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_company_news"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("AAPL");
        assertThat(args.getValue().path("from").asString()).isEqualTo("2026-06-28");
        assertThat(args.getValue().path("to").asString()).isEqualTo("2026-07-01");
    }

    @Test void newsReturnsEmptyListOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(data(client, false).news("AAPL", LocalDate.now().minusDays(1), LocalDate.now()))
                .isEmpty();
    }

    @Test void newsDefaultsSourceTypeToNewsWhenFieldMissing() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Old Agora item without sourceType\",\"summary\":\"s\",\"source\":\"WSJ\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"New Agora item with sourceType\",\"summary\":\"s\",\"source\":\"yahoo-rss\"," +
                "\"sourceType\":\"news\",\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = data(client, false);

        List<NewsHeadline> out = data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).sourceType()).isEqualTo("news"); // missing field defaults to "news"
        assertThat(out.get(1).sourceType()).isEqualTo("news"); // explicit field passed through
    }

    @Test void newsDropsDatelessItemsWithDebugCount() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Good item\",\"summary\":\"s\",\"source\":\"WSJ\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"No datetime field\",\"summary\":\"s\",\"source\":\"yahoo-rss\"," +
                "\"url\":\"http://n/2\"}," +
                "{\"headline\":\"Unparseable datetime\",\"summary\":\"s\",\"source\":\"yahoo-rss\"," +
                "\"datetime\":\"Tue, 15 Jul 2026 10:00:00 EST\",\"url\":\"http://n/3\"}]}"));
        AgoraCompanyData data = data(client, false);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgoraCompanyData.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            List<NewsHeadline> out =
                    data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).headline()).isEqualTo("Good item");
            assertThat(appender.list)
                    .anySatisfy(e -> assertThat(e.getFormattedMessage())
                            .isEqualTo("news: dropped 2 dateless items for AAPL"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }

    @Test void newsSendsSourceTypesNewsArgAndFiltersSocialWhenIncludeSocialFalse() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Real news\",\"summary\":\"s\",\"source\":\"WSJ\"," +
                "\"sourceType\":\"news\",\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"Forum chatter\",\"summary\":\"s\",\"source\":\"reddit-stocks\"," +
                "\"sourceType\":\"social\",\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = data(client, false);

        List<NewsHeadline> out =
                data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).headline()).isEqualTo("Real news");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_company_news"), args.capture());
        assertThat(args.getValue().path("sourceTypes").isArray()).isTrue();
        assertThat(args.getValue().path("sourceTypes")).hasSize(1);
        assertThat(args.getValue().path("sourceTypes").get(0).asString()).isEqualTo("news");
    }

    @Test void newsOmitsSourceTypesArgWhenIncludeSocialTrueAndCredibilityStillDropsReddit() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Real news\",\"summary\":\"s\",\"source\":\"WSJ\"," +
                "\"sourceType\":\"news\",\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}," +
                "{\"headline\":\"Forum chatter\",\"summary\":\"s\",\"source\":\"reddit-stocks\"," +
                "\"sourceType\":\"social\",\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = data(client, true);

        List<NewsHeadline> out =
                data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).headline()).isEqualTo("Real news");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_company_news"), args.capture());
        assertThat(args.getValue().has("sourceTypes")).isFalse();
    }

    @Test void newsLogsFilteredSocialCountAtDebug() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Forum chatter\",\"summary\":\"s\",\"source\":\"reddit-stocks\"," +
                "\"sourceType\":\"social\",\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"http://n/2\"}]}"));
        AgoraCompanyData data = data(client, false);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgoraCompanyData.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            assertThat(data.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16")))
                    .isEmpty();
            assertThat(appender.list)
                    .anySatisfy(e -> assertThat(e.getFormattedMessage())
                            .isEqualTo("news: filtered 1 social items for AAPL"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }

    @Test void recommendationsMapsCounts() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"recommendations\":[" +
                "{\"period\":\"2026-06-01\",\"strongBuy\":10,\"buy\":5,\"hold\":3,\"sell\":1,\"strongSell\":0}," +
                "{\"period\":\"2026-05-01\",\"strongBuy\":8,\"buy\":5,\"hold\":4,\"sell\":2,\"strongSell\":1}]}"));
        AgoraCompanyData data = data(client, false);

        List<RecommendationTrend> out = data.recommendations("AAPL");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).period()).isEqualTo("2026-06-01");
        assertThat(out.get(0).strongBuy()).isEqualTo(10);
        assertThat(out.get(0).buy()).isEqualTo(5);
        assertThat(out.get(0).hold()).isEqualTo(3);
        assertThat(out.get(0).sell()).isEqualTo(1);
        assertThat(out.get(0).strongSell()).isEqualTo(0);
    }

    @Test void recommendationsReturnsEmptyListOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_analyst_estimates"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(data(client, false).recommendations("AAPL")).isEmpty();
    }

    @Test void fundamentalsReturnsRawMetricsBlob() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"metrics\":{\"beta\":1.2,\"52WeekLow\":150.0,\"roaTTM\":21.5}}"));
        JsonNode m = data(client, false).fundamentals("AAPL");
        assertThat(m).isNotNull();
        assertThat(m.path("beta").asDouble()).isEqualTo(1.2);
        assertThat(m.path("52WeekLow").asDouble()).isEqualTo(150.0);
    }

    @Test void fundamentalsReturnsNullOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(data(client, false).fundamentals("AAPL")).isNull();
    }

    @Test void fundamentalsReturnsNullWhenMetricsMissingOrNull() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamentals"), any())).thenReturn(json("{\"symbol\":\"AAPL\",\"metrics\":null}"));
        assertThat(data(client, false).fundamentals("AAPL")).isNull();
    }

    @Test void profileReturnsRawBlobAndNullOnFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_profile"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"profile\":{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\"}}"));
        JsonNode p = data(client, false).profile("AAPL");
        assertThat(p).isNotNull();
        assertThat(p.path("finnhubIndustry").asString()).isEqualTo("Technology");

        AgoraClient down = Mockito.mock(AgoraClient.class);
        when(down.callTool(eq("get_company_profile"), any())).thenThrow(new AgoraUnavailableException("down"));
        assertThat(data(down, false).profile("AAPL")).isNull();
    }

    // ---- T1.4 credibility scoring at the chokepoint ----

    @Test void newsDropsLowCredibilityItemsWithInfoSummaryLog() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Wire story\",\"summary\":\"s\",\"source\":\"Reuters\",\"sourceType\":\"news\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"https://www.reuters.com/a\",\"domain\":\"reuters.com\"}," +
                "{\"headline\":\"Forum chatter\",\"summary\":\"s\",\"source\":\"reddit-stocks\",\"sourceType\":\"social\"," +
                "\"datetime\":\"2026-07-15T11:00:00Z\",\"url\":\"https://old.reddit.com/r/stocks/x\",\"domain\":\"old.reddit.com\"}]}"));
        AgoraCompanyData cd = data(client, true);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgoraCompanyData.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<NewsHeadline> out =
                    cd.news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).headline()).isEqualTo("Wire story");
            // R1 Minor 7: credibility drops are operator-policy outcomes -> INFO, not DEBUG.
            assertThat(appender.list)
                    .anySatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.INFO);
                        assertThat(e.getFormattedMessage())
                                .isEqualTo("news: dropped 1 low-credibility items for AAPL");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test void newsSurvivorCarriesCredibilityAndDomain() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Wire story\",\"summary\":\"s\",\"source\":\"Reuters\",\"sourceType\":\"news\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"https://www.reuters.com/a\",\"domain\":\"reuters.com\"}]}"));
        List<NewsHeadline> out = data(client, false)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).domain()).isEqualTo("reuters.com");
        assertThat(out.get(0).credibility()).isEqualTo(0.9);
    }

    @Test void newsBoundaryScoreEqualToDropBelowPasses() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Boundary item\",\"summary\":\"s\",\"source\":\"x\",\"sourceType\":\"news\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"https://boundary.example/a\",\"domain\":\"boundary.example\"}]}"));
        List<NewsHeadline> out = data(client, false)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1); // drop iff score < dropBelow; 0.3 == 0.3 passes
        assertThat(out.get(0).credibility()).isEqualTo(0.3);
    }

    @Test void newsRedditGuaranteeUnknownDomainDropsViaSourceFallback() {
        // Locks the opt-in mechanism regardless of reddit's actual link host.
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Reddit post\",\"summary\":\"s\",\"source\":\"reddit-stocks\",\"sourceType\":\"social\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"https://some.unknownhost.example/x\",\"domain\":\"some.unknownhost.example\"}]}"));
        assertThat(data(client, true)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"))).isEmpty();
    }

    @Test void newsAcceptedLossPinRedditSourceWithWireDomainStillDrops() {
        // R3 accepted-loss decision as an executable test: min(0.2, 0.9) < 0.3 -> drop.
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Reddit link-post to a wire story\",\"summary\":\"s\",\"source\":\"reddit-stocks\"," +
                "\"sourceType\":\"social\",\"datetime\":\"2026-07-15T10:00:00Z\"," +
                "\"url\":\"https://www.reuters.com/a\",\"domain\":\"reuters.com\"}]}"));
        assertThat(data(client, true)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"))).isEmpty();
    }

    @Test void newsOldAgoraPayloadWithoutDomainKeyScoresViaSourceFallbackAndSurvives() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Old Agora item\",\"summary\":\"s\",\"source\":\"SomeWire\"," +
                "\"datetime\":\"2026-07-15T10:00:00Z\",\"url\":\"http://n/1\"}]}"));
        List<NewsHeadline> out = data(client, false)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).domain()).isNull();
        assertThat(out.get(0).credibility()).isEqualTo(0.5); // unknown source -> defaultScore
    }

    @Test void newsExplicitNullDomainNeverHitsAnEmptyStringTableRow() {
        // New-Agora unparsable-URL shape: "domain": null. The "" table row must not fire.
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_company_news"), any())).thenReturn(json(
                "{\"symbol\":\"AAPL\",\"news\":[" +
                "{\"headline\":\"Null domain item\",\"summary\":\"s\",\"source\":\"\"," +
                "\"sourceType\":\"news\",\"datetime\":\"2026-07-15T10:00:00Z\"," +
                "\"url\":\"not a url\",\"domain\":null}]}"));
        List<NewsHeadline> out = data(client, false)
                .news("AAPL", LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-16"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).credibility()).isEqualTo(0.5); // NOT the "" row's 0.1
    }
}
