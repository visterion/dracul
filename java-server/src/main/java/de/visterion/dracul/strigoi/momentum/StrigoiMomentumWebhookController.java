package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * strigoi-momentum (spec 2026-10-04 §5): code ranks the S&P 500 by 12-1 momentum and decides the
 * monthly rebalance; the LLM may only veto. One uncached tool, {@code fetch_momentum_ranking},
 * whose answer is stored per Vistierie run ({@code X-Vistierie-Run-Id}) as the snapshot the
 * completion reads. LLM prey are never persisted — the completion builds the prey itself.
 */
@RestController
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-momentum")
public class StrigoiMomentumWebhookController extends HuntController {

    static final String AGENT = MomentumSettings.AGENT;

    private final MomentumRankingService ranking;

    public StrigoiMomentumWebhookController(
            @Value("${dracul.strigoi.momentum.webhook-token}") String token,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            MomentumRankingService ranking) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.ranking = ranking;
    }

    @Override protected String agentName() { return AGENT; }
    @Override protected String defaultAnomalyType() { return ExitProfile.MOMENTUM_12_1; }
    @Override protected String defaultHorizon() { return MomentumSettings.HORIZON; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return MomentumDefaults.FETCH; }
    @Override protected String fetchOutputKey() { return "ranking"; }

    /** Not routed: the tool has its own, uncached endpoint below. */
    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> input) {
        return DataSourceResult.unavailable("dracul", "strigoi-momentum has no generic fetch");
    }

    @PostMapping("/tools/fetch-ranking")
    public ResponseEntity<Map<String, Object>> fetchRanking(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        if (runId == null || runId.isBlank()) {
            log.warn("{} tool {}: no X-Vistierie-Run-Id header — the ranking snapshot cannot be "
                    + "keyed, answering unavailable", AGENT, MomentumDefaults.FETCH);
            return ok(unavailable(Map.of("ranking", Map.of()), AGENT,
                    GUARD_MARKER + "missing X-Vistierie-Run-Id header"));
        }
        try {
            return ok(ranking.rank(runId.trim()));
        } catch (RuntimeException e) {
            log.warn("{} tool {} failed — answering unavailable instead of 4xx: {}", AGENT,
                    MomentumDefaults.FETCH, e.toString(), e);
            return ok(unavailable(Map.of("ranking", Map.of()), AGENT, GUARD_MARKER + e));
        }
    }

    /** LLM prey are ignored: momentum prey are built by code from the stored ranking (Task 7). */
    @Override
    protected List<Prey> selectForPersist(List<Prey> mapped, JsonNode body, String runId) {
        if (!mapped.isEmpty()) {
            log.warn("{} run {}: {} LLM prey ignored — momentum prey are built by code from the "
                    + "ranking snapshot", AGENT, runId, mapped.size());
        }
        return List.of();
    }
}
