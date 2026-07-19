package de.visterion.dracul.inspector;

import de.visterion.dracul.vistierie.RunSearchHit;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** Operator-facing read-only inspector over ALL Vistierie agent runs (not prey-scoped: these are
 *  the tenant's own agents). Behind CF Access like every /api/** endpoint. The prey-gated per-trade
 *  transcript (DepotController.transcript) is a separate path and intentionally NOT touched. */
@RestController
@RequestMapping("/api/inspector")
public class InspectorController {

    private final VistierieClient vistierie;

    public InspectorController(VistierieClient vistierie) {
        this.vistierie = vistierie;
    }

    @GetMapping("/runs")
    public RunsResponse runs(@RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        int capped = Math.min(Math.max(limit, 1), 200);
        List<RunView> runs = vistierie.listAgentRuns(agent, capped, Math.max(offset, 0)).stream()
                .map(h -> new RunView(h.runId(), h.agent(), h.status(), h.hasError(), h.startedAt(), h.snippet()))
                .toList();
        return new RunsResponse(runs);
    }

    @GetMapping("/run/{runId}/transcript")
    public TranscriptResponse transcript(@PathVariable String runId) {
        JsonNode node = vistierie.getRunTranscript(runId, "full");
        return new TranscriptResponse(node, node == null);
    }

    public record RunsResponse(List<RunView> runs) {}

    public record RunView(String runId, String agent, String status, boolean hasError,
            Instant startedAt, String snippet) {}

    public record TranscriptResponse(JsonNode transcript, boolean expired) {}
}
