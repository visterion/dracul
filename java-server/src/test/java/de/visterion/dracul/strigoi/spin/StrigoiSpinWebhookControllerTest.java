package de.visterion.dracul.strigoi.spin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D5 (#47): the webhook's belay against a hallucinated term-sheet date. All fixtures are
 * synthetic — an invented company ("Acme Spinco", ticker ACME), invented dates.
 *
 * <p>{@code terms} in the completion payload is applied BEFORE the normal prey processing (plan
 * step 6), so every test here posts a completion with an empty {@code prey} array (except the
 * fail-soft test, which needs a real one) and asserts on {@link SpinCandidateRepository}
 * interactions.
 *
 * <p>Fix-round-1 (I-2): the join key is the candidate's stable {@code id}, not {@code symbol} —
 * {@code symbol} is blank on exactly the pre-distribution rows whose term sheet carries the most
 * valuable reading. {@code symbol} still rides along purely as a human-readable log label.
 */
class StrigoiSpinWebhookControllerTest {

    private static final String BEARER = "Bearer tok";
    private static final long ROW_ID = 42L;

    private static JsonNode json(String s) throws Exception {
        return JsonMapper.builder().build().readTree(s);
    }

    private SpinCandidateRepository spinRepo;
    private PreyRepository preyRepo;
    private StrigoiSpinWebhookController controller;

    private final Logger controllerLog =
            (Logger) org.slf4j.LoggerFactory.getLogger(StrigoiSpinWebhookController.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private StrigoiSpinWebhookController newController() {
        spinRepo = mock(SpinCandidateRepository.class);
        preyRepo = mock(PreyRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);
        var c = new StrigoiSpinWebhookController(
                "tok",
                mock(AgoraFilings.class),
                mock(SpinoffScreener.class),
                spinRepo,
                mock(SpinLifecycleReconciler.class),
                mock(SpinCandidateEnricher.class),
                preyRepo,
                cache,
                mock(HiveMemResearchService.class),
                mock(ResearchMemoryLinkRepository.class),
                60, 90);
        // patternRepo / signalEmitter are @Autowired FIELDS on HuntController (see
        // HuntControllerUnavailableLoudnessTest for the same workaround); a hand-built controller
        // outside a Spring context leaves them null, and complete() would NPE on signalEmitter
        // before this suite ever gets to assert anything.
        org.springframework.test.util.ReflectionTestUtils.setField(
                c, de.visterion.dracul.webhook.HuntController.class, "patternRepo", emptyProvider(), null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                c, de.visterion.dracul.webhook.HuntController.class, "signalEmitter", emptyProvider(), null);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return (org.springframework.beans.factory.ObjectProvider<T>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private void startLogCapture() {
        appender.start();
        controllerLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        controllerLog.detachAppender(appender);
    }

    private static SpinCandidateRow row(String termSheetText) {
        return new SpinCandidateRow(ROW_ID, "0000000001", "ACME", "Acme Spinco", "10-12B",
                LocalDate.of(2026, 1, 1), "https://example.com/filing", "one for two",
                null, null, true, termSheetText, "PARENT",
                SpinStatus.DISTRIBUTED, null, null, null,
                null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z", null, null, null);
    }

    @Test
    void recordDateEntryWithVerifiedEvidenceIsAcceptedAndJoinedById() throws Exception {
        controller = newController();
        String termSheet = "The record date for the distribution will be 2026-02-01, as fixed by "
                + "the Acme Spinco board of directors.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-1", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date for the distribution will be 2026-02-01, as fixed by \
the Acme Spinco board of directors."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, LocalDate.of(2026, 2, 1), null);
    }

    @Test
    void distributionDateEntryWithVerifiedEvidenceIsAccepted() throws Exception {
        controller = newController();
        String termSheet = "The distribution date, set by the Acme Spinco board, will be 2026-03-02.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-2", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":null,"distributionDate":"2026-03-02",
                   "evidence":"The distribution date, set by the Acme Spinco board, will be 2026-03-02."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, null, LocalDate.of(2026, 3, 2));
    }

    /** Two entries for the SAME candidate id, one per date, each with its own single-date
     *  evidence sentence — the realistic shape once a sentence can carry at most one date under
     *  the corrected rules. */
    @Test
    void twoEntriesForTheSameIdEachVerifyIndependently() throws Exception {
        controller = newController();
        String termSheet = "The record date for the distribution will be 2026-02-01. "
                + "The distribution date, set by the Acme Spinco board, will be 2026-03-02.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-2b", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date for the distribution will be 2026-02-01."},
                  {"id":42,"symbol":"ACME","recordDate":null,"distributionDate":"2026-03-02",
                   "evidence":"The distribution date, set by the Acme Spinco board, will be 2026-03-02."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, LocalDate.of(2026, 2, 1), null);
        verify(spinRepo).storeVerifiedDates(ROW_ID, null, LocalDate.of(2026, 3, 2));
    }

    @Test
    void evidenceThatDoesNotContainTheDeliveredDateIsRejected() throws Exception {
        controller = newController();
        String termSheet = "The record date is 2026-02-01. Nothing else is said about it, ever.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-3", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"Nothing else is said about it, ever."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void evidenceNotFoundInTheStoredTermSheetIsRejected() throws Exception {
        controller = newController();
        String termSheet = "This filing never mentions any record date at all, on any page.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-4", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01, fabricated by the model."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void unknownIdIsIgnoredAndNothingIsWritten() throws Exception {
        controller = newController();
        when(spinRepo.findById(999L)).thenReturn(Optional.empty());

        controller.complete(BEARER, "run-5", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":999,"symbol":"GHOST","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void missingIdIsIgnoredAndNothingIsWritten() throws Exception {
        controller = newController();

        controller.complete(BEARER, "run-5b", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01."}
                ]}}
                """));

        verify(spinRepo, never()).findById(anyLong());
        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    /** {@code distributionDate = null} must never overwrite an already-stored date — the
     *  repository call only carries the recordDate, leaving distributionDate untouched via the
     *  method's own COALESCE semantics; the controller simply must not pass a rejecting/absent
     *  value as if it were a verified null-out. */
    @Test
    void nullDistributionDateDoesNotOverwriteAnExistingOne() throws Exception {
        controller = newController();
        String termSheet = "The record date is 2026-02-01, as fixed by the Acme Spinco board.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-6", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01, as fixed by the Acme Spinco board."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, LocalDate.of(2026, 2, 1), null);
    }

    // ================================================================================
    // Fix-round-1, C-1 regression: a sentence naming BOTH dates must reject either field.
    // ================================================================================

    @Test
    void aSentenceNamingBothDatesIsRejectedNotSilentlyAccepted() throws Exception {
        controller = newController();
        String termSheet = "The record date is March 2, 2026 and the distribution date is "
                + "March 16, 2026.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-7", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":null,"distributionDate":"2026-03-02",
                   "evidence":"The record date is March 2, 2026 and the distribution date is \
March 16, 2026."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    // ================================================================================
    // Fix-round-1, I-4: rejected readings must actually be logged (WARN) and counted (summary).
    // ================================================================================

    @Test
    void rejectedReadingIsLoggedAtWarnAndCountedInTheSummary() throws Exception {
        controller = newController();
        startLogCapture();
        String termSheet = "This filing never mentions any record date at all, on any page.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-8", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"fabricated, not in the filing, but long enough to pass the length rule"}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
        assertThat(appender.list)
                .as("a WARN naming the rejected reading")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("recordDate")
                        && e.getFormattedMessage().contains("2026-02-01")
                        && e.getFormattedMessage().contains("ACME"));
        assertThat(appender.list)
                .as("the accepted/rejected summary line")
                .anyMatch(e -> e.getFormattedMessage().contains("accepted=0")
                        && e.getFormattedMessage().contains("rejected=1"));
    }

    @Test
    void acceptedReadingIsCountedInTheSummary() throws Exception {
        controller = newController();
        startLogCapture();
        String termSheet = "The record date for the distribution will be 2026-02-01, as fixed by "
                + "the Acme Spinco board of directors.";
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-9", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date for the distribution will be 2026-02-01, as fixed by \
the Acme Spinco board of directors."}
                ]}}
                """));

        assertThat(appender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("accepted=1")
                        && e.getFormattedMessage().contains("rejected=0"));
    }

    // ================================================================================
    // Fix-round-1, M-2: "no term_sheet_text at all" must be logged/counted distinctly from a
    // rejected (fabricated) evidence reading -- the two mean different things.
    // ================================================================================

    @Test
    void missingTermSheetTextIsDistinctFromARejectedReading() throws Exception {
        controller = newController();
        startLogCapture();
        when(spinRepo.findById(ROW_ID)).thenReturn(Optional.of(row(null)));

        controller.complete(BEARER, "run-10", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date for the distribution will be 2026-02-01."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
        assertThat(appender.list)
                .as("no WARN -- this is not a rejected/fabricated reading")
                .noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(appender.list)
                .as("a distinct summary counter, not folded into rejected")
                .anyMatch(e -> e.getFormattedMessage().contains("skippedNoText=1")
                        && e.getFormattedMessage().contains("rejected=0"));
    }

    @Test
    void nonDoneStatusAppliesNoTerms() throws Exception {
        controller = newController();

        controller.complete(BEARER, "run-11", json("""
                {"status":"failed","output":{"prey":[],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"whatever"}
                ]}}
                """));

        verifyNoInteractions(spinRepo);
    }

    @Test
    void missingTermsBlockIsANoOp() throws Exception {
        controller = newController();

        controller.complete(BEARER, "run-12", json("""
                {"status":"done","output":{"prey":[]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    // ================================================================================
    // Fix-round-1, I-1: applyTerms must be fail-soft -- a DB error while verifying terms must
    // never cost the prey this same completion is delivering.
    // ================================================================================

    @Test
    void aTermsFailureDoesNotPreventPreyFromBeingPersisted() throws Exception {
        controller = newController();
        when(spinRepo.findById(anyLong())).thenThrow(new RuntimeException("transient db hiccup"));
        var inserted = new Prey("id-1", "ACME", "Acme Spinco", "SPINOFF", 0.7, "thesis",
                List.of(), List.of(), List.of("kill"), "6m", "strigoi-spin",
                java.time.Instant.now().toString());
        when(preyRepo.insertAll(anyList(), anyString())).thenReturn(List.of(inserted));

        controller.complete(BEARER, "run-13", json("""
                {"status":"done","output":{"prey":[
                  {"symbol":"ACME","companyName":"Acme Spinco","confidence":0.7,"thesis":"t",
                   "horizon":"6m","kill_criteria":["k"]}
                ],"terms":[
                  {"id":42,"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"whatever, long enough to pass the minimum evidence length rule"}
                ]}}
                """));

        verify(preyRepo).insertAll(anyList(), eq("run-13"));
    }
}
