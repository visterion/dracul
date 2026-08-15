package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
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
 * step 6), so every test here posts a completion with an empty {@code prey} array and asserts
 * only on {@link SpinCandidateRepository} interactions.
 */
class StrigoiSpinWebhookControllerTest {

    private static final String BEARER = "Bearer tok";
    private static final long ROW_ID = 42L;

    private static JsonNode json(String s) throws Exception {
        return JsonMapper.builder().build().readTree(s);
    }

    private SpinCandidateRepository spinRepo;
    private StrigoiSpinWebhookController controller;

    private StrigoiSpinWebhookController newController() {
        spinRepo = mock(SpinCandidateRepository.class);
        var cache = new ToolFetchCache(new AgentToolCatalog(List.of()), 0);
        return new StrigoiSpinWebhookController(
                "tok",
                mock(AgoraFilings.class),
                mock(SpinoffScreener.class),
                spinRepo,
                mock(SpinLifecycleReconciler.class),
                mock(SpinCandidateEnricher.class),
                mock(PreyRepository.class),
                cache,
                mock(HiveMemResearchService.class),
                mock(ResearchMemoryLinkRepository.class),
                60, 90);
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
    void evidenceQuotedVerbatimAndContainingBothDatesAcceptsBothFields() throws Exception {
        controller = newController();
        String termSheet = "The record date is 2026-02-01. The distribution date is 2026-03-02.";
        when(spinRepo.findActiveBySymbol("ACME")).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-1", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":"2026-03-02",
                   "evidence":"The record date is 2026-02-01. The distribution date is 2026-03-02."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 2));
    }

    @Test
    void evidenceThatDoesNotContainTheDeliveredDateIsRejected() throws Exception {
        controller = newController();
        String termSheet = "The record date is 2026-02-01. Nothing else is said about it.";
        when(spinRepo.findActiveBySymbol("ACME")).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-2", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"Nothing else is said about it."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void evidenceNotFoundInTheStoredTermSheetIsRejected() throws Exception {
        controller = newController();
        String termSheet = "This filing never mentions any record date at all.";
        when(spinRepo.findActiveBySymbol("ACME")).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-3", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01, fabricated by the model."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void unknownSymbolIsIgnoredAndNothingIsWritten() throws Exception {
        controller = newController();
        when(spinRepo.findActiveBySymbol("GHOST")).thenReturn(Optional.empty());

        controller.complete(BEARER, "run-4", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"GHOST","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01."}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    /** {@code distributionDate = null} must never overwrite an already-stored date — the
     *  repository call only carries the recordDate, leaving distributionDate untouched via the
     *  method's own COALESCE semantics; the controller simply must not pass a rejecting/absent
     *  value as if it were a verified null-out. */
    @Test
    void nullDistributionDateDoesNotOverwriteAnExistingOne() throws Exception {
        controller = newController();
        String termSheet = "The record date is 2026-02-01, as fixed by the board.";
        when(spinRepo.findActiveBySymbol("ACME")).thenReturn(Optional.of(row(termSheet)));

        controller.complete(BEARER, "run-5", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"The record date is 2026-02-01, as fixed by the board."}
                ]}}
                """));

        verify(spinRepo).storeVerifiedDates(ROW_ID, LocalDate.of(2026, 2, 1), null);
    }

    @Test
    void rejectedReadingIsLogged() throws Exception {
        controller = newController();
        String termSheet = "This filing never mentions any record date at all.";
        when(spinRepo.findActiveBySymbol("ACME")).thenReturn(Optional.of(row(termSheet)));

        // No assertion on log output directly (no test log appender wired here) -- this test
        // instead pins that a rejected reading does not throw and does not write anything,
        // which is the externally observable half of "rejected readings are logged and counted".
        controller.complete(BEARER, "run-6", json("""
                {"status":"done","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"fabricated, not in the filing"}
                ]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }

    @Test
    void nonDoneStatusAppliesNoTerms() throws Exception {
        controller = newController();

        controller.complete(BEARER, "run-7", json("""
                {"status":"failed","output":{"prey":[],"terms":[
                  {"symbol":"ACME","recordDate":"2026-02-01","distributionDate":null,
                   "evidence":"whatever"}
                ]}}
                """));

        verifyNoInteractions(spinRepo);
    }

    @Test
    void missingTermsBlockIsANoOp() throws Exception {
        controller = newController();

        controller.complete(BEARER, "run-8", json("""
                {"status":"done","output":{"prey":[]}}
                """));

        verify(spinRepo, never()).storeVerifiedDates(anyLong(), any(), any());
    }
}
