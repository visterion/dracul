package de.visterion.dracul.renfield;

import de.visterion.dracul.events.SseBroadcaster;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completion webhook for the renfield daily review. Single-owner by design (spec §B4):
 * proposals always belong to the primary user — no per-watcher fan-out, which keeps
 * UNIQUE (run_id, symbol) consistent. Delivery is idempotent against Vistierie's
 * webhook retries: Telegram + SSE fire only when at least one row was actually
 * inserted. Accepted edge: if the first attempt inserts rows but Dracul dies before
 * Telegram, the retry inserts zero rows and no Telegram is ever sent for that run —
 * the proposals are still in the table/UI. NO auto-trade: persist + report only.
 */
@RestController
@ConditionalOnProperty(value = "dracul.renfield.enabled", havingValue = "true")
@RequestMapping("/api/renfield")
public class RenfieldWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RenfieldWebhookController.class);
    private static final Set<String> ACTIONS =
            Set.of("buy", "add", "trim", "sell", "hold", "drop_from_watchlist");

    private final BearerTokenVerifier verifier;
    private final String owner;
    private final TradeProposalRepository proposals;
    private final TelegramNotifier notifier;
    private final SseBroadcaster broadcaster;
    private final HiveMemResearchService memory;
    private final ObjectMapper mapper;
    private final boolean backfillEnabled;
    private final RenfieldRunContextRepository runContext;

    public RenfieldWebhookController(
            @Value("${dracul.renfield.webhook-token}") String token,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.renfield.backfill-enabled:false}") boolean backfillEnabled,
            TradeProposalRepository proposals,
            TelegramNotifier notifier,
            SseBroadcaster broadcaster,
            HiveMemResearchService memory,
            ObjectMapper mapper,
            RenfieldRunContextRepository runContext) {
        this.verifier = new BearerTokenVerifier(token);
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.proposals = proposals;
        this.notifier = notifier;
        this.broadcaster = broadcaster;
        this.memory = memory;
        this.mapper = mapper;
        this.backfillEnabled = backfillEnabled;
        this.runContext = runContext;
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestHeader(value = "X-Dracul-Backfill", required = false) String backfillHeader,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        // One-off backfill seam (T1.6 repair): only live when the property is also on,
        // so the header alone can never silence alerting on the live endpoint.
        boolean backfill = backfillEnabled && "true".equalsIgnoreCase(backfillHeader);

        // Vistierie's successful agent-run status is "done" (AgentRunner); "succeeded"
        // is kept for defensive compatibility with tests/fixtures. "ok" is NOT success.
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("renfield run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }

        JsonNode o = body.path("output");
        String marketNote = o.path("market_note").asText("");
        var valid = new ArrayList<JsonNode>();
        for (JsonNode p : o.path("proposals")) {
            String symbol = p.path("symbol").asText("");
            String action = p.path("action").asText("");
            if (symbol.isBlank() || !ACTIONS.contains(action)) {
                log.warn("renfield run {} malformed proposal (symbol='{}', action='{}') — dropping",
                        runId, symbol, action);
                continue;
            }
            valid.add(p);
        }

        if (valid.isEmpty()) {
            // Still tell the user the run happened (spec §B4) -- unless this is a
            // backfill replay, which must stay silent end to end.
            if (!backfill) {
                notifier.notifyDigest("🧾 Renfield Watchlist-Review — keine Vorschläge heute.\n"
                        + marketNote);
            }
            return ResponseEntity.noContent().build();
        }

        int inserted = 0;
        int flaggedBuyOnHeld = 0;
        for (JsonNode p : valid) {
            String symbol = p.path("symbol").asText();
            String action = p.path("action").asText();
            if ("buy".equals(action) && checkBuyOnHeld(runId, symbol)) {
                flaggedBuyOnHeld++;
            }
            String rationale = p.path("rationale").asText("");
            BigDecimal confidence = p.path("confidence").isNumber()
                    ? new BigDecimal(p.path("confidence").asText()) : null;
            JsonNode newsSentiment = p.path("news_sentiment");
            String newsSentimentJson = newsSentiment.isArray()
                    ? mapper.writeValueAsString(newsSentiment) : null;
            int rowsInserted = proposals.insert(owner, symbol, action,
                    p.path("entry_zone").asText(""), p.path("stop").asText(""), confidence,
                    rationale, marketNote, runId, newsSentimentJson);
            inserted += rowsInserted;
            if (rowsInserted > 0 && !backfill) {
                // Cell-only write-back (T1.6 Task 9): per-proposal, not per-batch -- no
                // research_memory_link row (proposals never resolve to a Task-10 outcome the
                // way prey do). Best-effort: writeThesisMemory is itself guarded/never-throwing;
                // the outer try/catch is defense-in-depth so a bug here can't 500 the completion.
                // Suppressed during a backfill replay: searchForInput reads cells back without
                // filtering on kind, so replaying the same thesis N times would poison
                // prior_memory with the agent's own repetition.
                try {
                    memory.writeThesisMemory("trade_proposal", symbol, action, rationale,
                            List.of(), List.of(), List.of(), null, "renfield",
                            confidence == null ? 0.0 : confidence.doubleValue(), runId);
                } catch (RuntimeException e) {
                    log.warn("renfield run {} — memory write for {} failed unexpectedly: {}",
                            runId, symbol, e.getMessage());
                }
            }
        }

        if (inserted > 0 && backfill) {
            log.info("renfield run {} backfill replay — persisted {} of {} proposal(s), "
                    + "suppressing Telegram/SSE/memory", runId, inserted, valid.size());
        } else if (inserted > 0) {
            notifier.notifyDigest(render(valid, marketNote));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", valid.size());
            payload.put("run_id", runId);
            payload.put("ts", Instant.now().toString());
            broadcaster.sendToOwner(owner, "proposal.new", payload);
        } else {
            log.info("renfield run {} retried delivery — 0 new rows, suppressing Telegram/SSE", runId);
        }
        log.info("renfield run {} persisted {} of {} proposal(s), {} flagged buy-on-held",
                runId, inserted, valid.size(), flaggedBuyOnHeld);
        return ResponseEntity.noContent().build();
    }

    /**
     * F4 (spec 2026-08-08 §F4): a {@code buy} proposal for a symbol the trigger-time
     * snapshot marked as held is almost certainly wrong. Logged as WARN, never dropped
     * — the rationale stays useful even with an unsound label. Checked against the
     * snapshot taken at trigger time ({@link RenfieldRunContextRepository}), never
     * against a fresh depot/watchlist read: the completion arrives ~90s after the
     * trigger, and a depot that has since become unreachable would make a fresh check
     * pass silently exactly when it matters most.
     *
     * <p>Persistence of the proposal never depends on this check: a missing snapshot
     * (backfill replay, or a manually-triggered run outside the scheduler) logs INFO —
     * visibly "not checked", never silently "checked and clean" — and an unexpected
     * failure of the lookup itself logs WARN and is treated the same as "not checked".
     *
     * @return true iff a WARN was logged (row found and held)
     */
    private boolean checkBuyOnHeld(String runId, String symbol) {
        try {
            var row = runContext.findBySymbol(runId, symbol);
            if (row.isEmpty()) {
                log.info("renfield run {} — no run-context snapshot for {}, buy-on-held check "
                        + "skipped (expected for a backfill replay or a manually-triggered run)",
                        runId, symbol);
                return false;
            }
            if (row.get().held()) {
                log.warn("renfield run {} — buy proposal for {} conflicts with the trigger-time "
                        + "snapshot (held=true, position_source={}); keeping the proposal, "
                        + "flagging only", runId, symbol, row.get().positionSource());
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("renfield run {} — run-context lookup for {} failed unexpectedly, "
                    + "buy-on-held check skipped: {}", runId, symbol, e.getMessage());
            return false;
        }
    }

    /** ONE bundled plain-text message per run (no parse_mode; German per convention). */
    private static String render(List<JsonNode> valid, String marketNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧾 Renfield Watchlist-Review — ").append(valid.size()).append(" Vorschläge\n");
        for (JsonNode p : valid) {
            sb.append("• ").append(p.path("action").asText().toUpperCase())
              .append(' ').append(p.path("symbol").asText());
            String zone = p.path("entry_zone").asText("");
            if (!zone.isBlank()) sb.append(" — Zone ").append(zone);
            String stop = p.path("stop").asText("");
            if (!stop.isBlank()) sb.append(", Stop ").append(stop);
            if (p.path("confidence").isNumber()) {
                sb.append(" (conf ").append(p.path("confidence").asText()).append(')');
            }
            sb.append('\n').append("  ").append(p.path("rationale").asText("")).append('\n');
        }
        if (!marketNote.isBlank()) sb.append("Marktnotiz: ").append(marketNote).append('\n');
        return sb.toString();
    }
}
