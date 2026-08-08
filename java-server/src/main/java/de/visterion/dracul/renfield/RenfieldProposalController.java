package de.visterion.dracul.renfield;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of renfield's daily watchlist review: {@code GET /api/renfield/proposals}
 * groups {@link TradeProposalRepository#findRecent} into one entry per run, newest
 * run first, so Chronicle can render "what renfield proposed" without replaying the
 * flat row list itself.
 *
 * <p>Deliberately its own class, not a second method on {@link RenfieldWebhookController}:
 * that controller carries a {@link de.visterion.dracul.webhook.BearerTokenVerifier} for
 * machine auth, and {@code WebhookExclusionParityTest} asserts every handler on a class
 * with that field must be covered by {@code CloudflareAccessFilter.EXCLUDED} — and every
 * handler NOT covered must NOT carry that field. This endpoint is the opposite of the
 * webhook: a human, Cloudflare-Access-gated read. Putting it on the webhook class would
 * make one of those two directions unsatisfiable for whichever handler doesn't match.
 *
 * <p>No {@code @ConditionalOnProperty(dracul.renfield.enabled)}, unlike the sibling
 * {@code ExecutorSignalController}: already-persisted proposal history must stay readable
 * even after the renfield agent itself is switched off — the property gates whether new
 * runs happen, not whether past ones remain visible.
 */
@RestController
@RequestMapping("/api/renfield")
public class RenfieldProposalController {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final TradeProposalRepository proposals;

    public RenfieldProposalController(TradeProposalRepository proposals) {
        this.proposals = proposals;
    }

    @GetMapping("/proposals")
    public List<ProposalRun> proposals(@RequestParam(defaultValue = "7") int days) {
        int clamped = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        List<TradeProposal> rows = proposals.findRecent(CurrentUserHolder.get(), clamped);

        // findRecent is ordered created_at DESC, ctid ASC — rows of the same run are
        // contiguous and appear before older runs, so a LinkedHashMap keyed by runId
        // reproduces "newest run first, insertion order within a run" without re-sorting.
        Map<String, RunAccumulator> byRun = new LinkedHashMap<>();
        for (TradeProposal row : rows) {
            RunAccumulator run = byRun.computeIfAbsent(row.runId(),
                    id -> new RunAccumulator(row.createdAt(), row.marketNote()));
            run.items.add(new ProposalItem(row.id(), row.symbol(), row.action(), row.entryZone(),
                    row.stop(), row.confidence(), row.rationale(), row.newsSentiment()));
        }

        List<ProposalRun> result = new ArrayList<>(byRun.size());
        byRun.forEach((runId, acc) ->
                result.add(new ProposalRun(runId, acc.createdAt, acc.marketNote, acc.items)));
        return result;
    }

    private static final class RunAccumulator {
        final Instant createdAt;
        final String marketNote;
        final List<ProposalItem> items = new ArrayList<>();

        RunAccumulator(Instant createdAt, String marketNote) {
            this.createdAt = createdAt;
            this.marketNote = marketNote;
        }
    }

    public record ProposalItem(
            String id,
            String symbol,
            String action,
            String entryZone,
            String stop,
            BigDecimal confidence,
            String rationale,
            JsonNode newsSentiment) {
    }

    public record ProposalRun(
            String runId,
            Instant createdAt,
            String marketNote,
            List<ProposalItem> proposals) {
    }
}
