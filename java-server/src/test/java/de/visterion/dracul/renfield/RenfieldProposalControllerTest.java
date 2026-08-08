package de.visterion.dracul.renfield;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RenfieldProposalControllerTest {

    @AfterEach
    void clearUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void groupsProposalsByRunNewestRunFirstPreservingRepositoryOrderWithinARun() {
        CurrentUserHolder.set("alice@example.com");
        var repo = mock(TradeProposalRepository.class);
        Instant newer = Instant.parse("2026-08-08T22:00:00Z");
        Instant older = Instant.parse("2026-08-07T22:00:00Z");
        // findRecent's own contract (see its javadoc) is created_at DESC, ctid ASC — the
        // controller must not re-sort, so the mock returns rows already in that order.
        var p1 = proposal("p-1", "run-2", "AAA", newer, "note-run-2");
        var p2 = proposal("p-2", "run-2", "BBB", newer, "note-run-2");
        var p3 = proposal("p-3", "run-1", "CCC", older, "note-run-1");
        when(repo.findRecent("alice@example.com", 7)).thenReturn(List.of(p1, p2, p3));

        var runs = new RenfieldProposalController(repo).proposals(7);

        assertThat(runs).extracting(RenfieldProposalController.ProposalRun::runId)
                .containsExactly("run-2", "run-1");
        assertThat(runs.get(0).marketNote()).isEqualTo("note-run-2");
        assertThat(runs.get(0).createdAt()).isEqualTo(newer);
        assertThat(runs.get(0).proposals()).extracting(RenfieldProposalController.ProposalItem::id)
                .containsExactly("p-1", "p-2");
        assertThat(runs.get(1).proposals()).extracting(RenfieldProposalController.ProposalItem::id)
                .containsExactly("p-3");
    }

    @Test
    void daysIsForwardedToTheRepositoryAndClampedToOneToNinety() {
        CurrentUserHolder.set("alice@example.com");
        var repo = mock(TradeProposalRepository.class);
        when(repo.findRecent(any(), any(Integer.class))).thenReturn(List.of());
        var controller = new RenfieldProposalController(repo);

        controller.proposals(30);
        verify(repo).findRecent(eq("alice@example.com"), eq(30));

        controller.proposals(0);
        verify(repo).findRecent(eq("alice@example.com"), eq(1));

        controller.proposals(500);
        verify(repo).findRecent(eq("alice@example.com"), eq(90));
    }

    @Test
    void differentDaysWindowsProduceDifferentIdSetsNotJustDifferentCounts() {
        // Per the task brief: assert on the ID SET the days window yields, not on row
        // counts — a param-ignoring controller could still coincidentally return the
        // right count for one window.
        CurrentUserHolder.set("alice@example.com");
        var repo = mock(TradeProposalRepository.class);
        var recent = proposal("p-recent", "run-1", "AAA", Instant.parse("2026-08-08T10:00:00Z"), "n");
        var old = proposal("p-old", "run-0", "BBB", Instant.parse("2026-07-10T10:00:00Z"), "n2");
        when(repo.findRecent("alice@example.com", 3)).thenReturn(List.of(recent));
        when(repo.findRecent("alice@example.com", 30)).thenReturn(List.of(recent, old));
        var controller = new RenfieldProposalController(repo);

        assertThat(idsOf(controller.proposals(3))).containsExactlyInAnyOrder("p-recent");
        assertThat(idsOf(controller.proposals(30))).containsExactlyInAnyOrder("p-recent", "p-old");
    }

    @Test
    void ownerComesFromCurrentUserHolderNotARequestParameter() {
        CurrentUserHolder.set("bob@example.com");
        var repo = mock(TradeProposalRepository.class);
        when(repo.findRecent(eq("bob@example.com"), any(Integer.class))).thenReturn(List.of());

        new RenfieldProposalController(repo).proposals(7);

        verify(repo).findRecent(eq("bob@example.com"), any(Integer.class));
    }

    private static List<String> idsOf(List<RenfieldProposalController.ProposalRun> runs) {
        return runs.stream()
                .flatMap(r -> r.proposals().stream())
                .map(RenfieldProposalController.ProposalItem::id)
                .toList();
    }

    private static TradeProposal proposal(
            String id, String runId, String symbol, Instant createdAt, String marketNote) {
        return new TradeProposal(id, symbol, "buy", "10-11", "9.5", new BigDecimal("0.7"),
                "rationale for " + symbol, marketNote, runId, createdAt, null);
    }
}
