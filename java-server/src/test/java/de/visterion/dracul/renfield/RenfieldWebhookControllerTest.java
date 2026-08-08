package de.visterion.dracul.renfield;

import de.visterion.dracul.events.SseBroadcaster;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenfieldWebhookControllerTest {

    private static final String BEARER = "Bearer tok";
    private static final String OWNER = "primary@x.com";

    private TradeProposalRepository proposals;
    private TelegramNotifier notifier;
    private SseBroadcaster broadcaster;
    private HiveMemResearchService memory;
    private RenfieldRunContextRepository runContext;
    private RenfieldWebhookController controller;
    private RenfieldWebhookController backfillController;

    @BeforeEach
    void setUp() {
        proposals = mock(TradeProposalRepository.class);
        notifier = mock(TelegramNotifier.class);
        broadcaster = mock(SseBroadcaster.class);
        memory = mock(HiveMemResearchService.class);
        runContext = mock(RenfieldRunContextRepository.class);
        controller = new RenfieldWebhookController("tok", OWNER, false, proposals, notifier,
                broadcaster, memory, new JsonMapper(), runContext);
        backfillController = new RenfieldWebhookController("tok", OWNER, true, proposals, notifier,
                broadcaster, memory, new JsonMapper(), runContext);
    }

    private static JsonNode json(String s) throws Exception {
        return JsonMapper.builder().build().readTree(s);
    }

    @Test
    void badToken_returns401() throws Exception {
        var resp = controller.complete("Bearer wrong", "run-1", null, json("""
                {"status":"done","output":{"proposals":[],"market_note":""}}
                """));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(proposals, notifier, broadcaster);
    }

    @Test
    void failedStatus_persistsNothingSendsNothing() throws Exception {
        var resp = controller.complete(BEARER, "run-2", null, json("""
                {"status":"failed","output":{"proposals":[{"symbol":"ACME","action":"buy",
                 "confidence":0.7,"rationale":"r"}],"market_note":"m"}}
                """));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(proposals, notifier, broadcaster);
    }

    @Test
    void okStatus_isNotTreatedAsSuccess() throws Exception {
        controller.complete(BEARER, "run-3", null, json("""
                {"status":"ok","output":{"proposals":[{"symbol":"ACME","action":"buy",
                 "confidence":0.7,"rationale":"r"}],"market_note":"m"}}
                """));
        verifyNoInteractions(proposals, notifier, broadcaster);
    }

    @Test
    void validPayload_persistsRowsForPrimaryOwnerSendsOneTelegramAndOneSse() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        var resp = controller.complete(BEARER, "run-4", null, json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"41.50-42.20","stop":"39.80",
                    "confidence":0.7,"rationale":"guidance cut priced in"},
                   {"symbol":"BETA","action":"trim","entry_zone":"","stop":"",
                    "confidence":0.6,"rationale":"stop proximity alert"}
                ],"market_note":"quiet tape"}}
                """));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        // V35 multi-owner guard: every row carries the single primary owner.
        verify(proposals).insert(eq(OWNER), eq("ACME"), eq("buy"), eq("41.50-42.20"),
                eq("39.80"), eq(new BigDecimal("0.7")), eq("guidance cut priced in"),
                eq("quiet tape"), eq("run-4"), isNull());
        verify(proposals).insert(eq(OWNER), eq("BETA"), eq("trim"), eq(""), eq(""),
                eq(new BigDecimal("0.6")), eq("stop proximity alert"), eq("quiet tape"), eq("run-4"),
                isNull());
        verify(notifier, times(1)).notifyDigest(contains("ACME"));
        verify(broadcaster, times(1)).sendToOwner(eq(OWNER), eq("proposal.new"), any());
    }

    @Test
    void duplicateDelivery_zeroRowsInserted_noSecondTelegramOrSse() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(0);

        controller.complete(BEARER, "run-5", null, json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r"}],"market_note":"m"}}
                """));

        verifyNoInteractions(notifier, broadcaster);
    }

    @Test
    void malformedProposal_droppedWithWarn_restKept() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        controller.complete(BEARER, "run-6", null, json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"short_squeeze","confidence":0.9,"rationale":"bad action"},
                   {"action":"buy","confidence":0.9,"rationale":"missing symbol"},
                   {"symbol":"GOOD","action":"hold","entry_zone":"","stop":"",
                    "confidence":0.5,"rationale":"fine"}
                ],"market_note":"m"}}
                """));

        verify(proposals, times(1)).insert(eq(OWNER), eq("GOOD"), eq("hold"), eq(""), eq(""),
                eq(new BigDecimal("0.5")), eq("fine"), eq("m"), eq("run-6"), isNull());
    }

    @Test
    void emptyProposals_stillSendsKeineVorschlaegeTelegram() throws Exception {
        controller.complete(BEARER, "run-7", null, json("""
                {"status":"done","output":{"proposals":[],"market_note":"nothing today"}}
                """));

        verify(notifier).notifyDigest(contains("keine Vorschläge heute"));
        verifyNoInteractions(proposals);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void newsSentimentArrayIsPassedThroughSerialized() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        controller.complete(BEARER, "run-8", null, json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r",
                    "news_sentiment":[{"headline":"ACME beats guidance","sentiment":"positive"}]}
                ],"market_note":"m"}}
                """));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(proposals).insert(eq(OWNER), eq("ACME"), eq("buy"), eq(""), eq(""),
                eq(new BigDecimal("0.7")), eq("r"), eq("m"), eq("run-8"), captor.capture());

        JsonNode roundTripped = new ObjectMapper().readTree(captor.getValue());
        assertThat(roundTripped.isArray()).isTrue();
        assertThat(roundTripped.get(0).path("headline").asText()).isEqualTo("ACME beats guidance");
        assertThat(roundTripped.get(0).path("sentiment").asText()).isEqualTo("positive");
    }

    @Test
    void nonArrayNewsSentimentBecomesNullAndTheRowStillPersists() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        controller.complete(BEARER, "run-9", null, json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r","news_sentiment":0.42}
                ],"market_note":"m"}}
                """));

        verify(proposals).insert(eq(OWNER), eq("ACME"), eq("buy"), eq(""), eq(""),
                eq(new BigDecimal("0.7")), eq("r"), eq("m"), eq("run-9"), isNull());
    }

    @Test
    void backfillHeaderSuppressesTelegramSseAndMemoryButPersists() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        var resp = backfillController.complete(BEARER, "run-10", "true", json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"AVGO","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r"}
                ],"market_note":"m"}}
                """));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(proposals).insert(eq(OWNER), eq("AVGO"), eq("buy"), eq(""), eq(""),
                eq(new BigDecimal("0.7")), eq("r"), eq("m"), eq("run-10"), isNull());
        verifyNoInteractions(notifier, broadcaster, memory);
    }

    @Test
    void backfillHeaderWithoutThePropertyIsIgnored() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);

        // controller (not backfillController) has backfill-enabled=false.
        controller.complete(BEARER, "run-11", "true", json("""
                {"status":"done","output":{"proposals":[
                   {"symbol":"AVGO","action":"buy","entry_zone":"","stop":"",
                    "confidence":0.7,"rationale":"r"}
                ],"market_note":"m"}}
                """));

        verify(notifier, times(1)).notifyDigest(contains("AVGO"));
        verify(broadcaster, times(1)).sendToOwner(eq(OWNER), eq("proposal.new"), any());
    }

    @Test
    void backfillHeaderAlsoSuppressesTheEmptyProposalsTelegram() throws Exception {
        backfillController.complete(BEARER, "run-12", "true", json("""
                {"status":"done","output":{"proposals":[],"market_note":"nothing today"}}
                """));

        verifyNoInteractions(notifier, proposals, broadcaster);
    }

    @Test
    void failedStatusPersistsNothingAndSendsNothing() throws Exception {
        var resp = controller.complete(BEARER, "run-13", null, json("""
                {"status":"failed","output":{"proposals":[{"symbol":"ACME","action":"buy",
                 "confidence":0.7,"rationale":"r"}],"market_note":"m"}}
                """));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(proposals, notifier, broadcaster);
    }

    @Test
    void buyOnHeldSymbolLogsWarnButPersists() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);
        when(runContext.findBySymbol("run-14", "ACME"))
                .thenReturn(Optional.of(new RunContextRow("ACME", true, "ok", java.time.Instant.now())));

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldWebhookController.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var resp = controller.complete(BEARER, "run-14", null, json("""
                    {"status":"done","output":{"proposals":[
                       {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                        "confidence":0.7,"rationale":"already in the depot"}
                    ],"market_note":"m"}}
                    """));

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verify(proposals).insert(eq(OWNER), eq("ACME"), eq("buy"), eq(""), eq(""),
                    eq(new BigDecimal("0.7")), eq("already in the depot"), eq("m"), eq("run-14"),
                    isNull());
            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(ev.getFormattedMessage()).contains("ACME").contains("held=true");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void missingSnapshotSkipsTheCheckAndLogsInfo() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);
        when(runContext.findBySymbol("run-15", "ACME")).thenReturn(Optional.empty());

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldWebhookController.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var resp = controller.complete(BEARER, "run-15", null, json("""
                    {"status":"done","output":{"proposals":[
                       {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                        "confidence":0.7,"rationale":"r"}
                    ],"market_note":"m"}}
                    """));

            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verify(proposals).insert(eq(OWNER), eq("ACME"), eq("buy"), eq(""), eq(""),
                    eq(new BigDecimal("0.7")), eq("r"), eq("m"), eq("run-15"), isNull());
            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(ev.getFormattedMessage())
                        .contains("no run-context snapshot").contains("ACME");
            });
            assertThat(appender.list).noneSatisfy(ev ->
                    assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void warnHoldsAndActionablesAreCountedSeparately() throws Exception {
        when(proposals.insert(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), anyString(), any())).thenReturn(1);
        when(runContext.findBySymbol("run-16", "ACME"))
                .thenReturn(Optional.of(new RunContextRow("ACME", true, "ok", java.time.Instant.now())));
        when(runContext.findBySymbol("run-16", "BETA"))
                .thenReturn(Optional.of(new RunContextRow("BETA", false, "ok", java.time.Instant.now())));

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RenfieldWebhookController.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.complete(BEARER, "run-16", null, json("""
                    {"status":"done","output":{"proposals":[
                       {"symbol":"ACME","action":"buy","entry_zone":"","stop":"",
                        "confidence":0.7,"rationale":"held already"},
                       {"symbol":"BETA","action":"buy","entry_zone":"","stop":"",
                        "confidence":0.6,"rationale":"not held"}
                    ],"market_note":"m"}}
                    """));

            assertThat(appender.list).anySatisfy(ev -> {
                assertThat(ev.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(ev.getFormattedMessage())
                        .contains("persisted 2 of 2").contains("1 flagged buy-on-held");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void controllerBeanAbsentWhenRenfieldDisabled() {
        var runner = new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withPropertyValues("dracul.renfield.webhook-token=tok",
                        "dracul.primary-user-email=primary@x.com")
                .withBean(TradeProposalRepository.class, () -> mock(TradeProposalRepository.class))
                .withBean(TelegramNotifier.class, () -> mock(TelegramNotifier.class))
                .withBean(SseBroadcaster.class, () -> mock(SseBroadcaster.class))
                .withBean(HiveMemResearchService.class, () -> mock(HiveMemResearchService.class))
                .withBean(ObjectMapper.class, JsonMapper::new)
                .withBean(RenfieldRunContextRepository.class,
                        () -> mock(RenfieldRunContextRepository.class))
                .withUserConfiguration(RenfieldWebhookController.class);
        // dracul.renfield.enabled defaults false → no bean.
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RenfieldWebhookController.class));
        runner.withPropertyValues("dracul.renfield.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(RenfieldWebhookController.class));
    }
}
