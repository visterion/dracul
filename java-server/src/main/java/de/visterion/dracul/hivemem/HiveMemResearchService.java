package de.visterion.dracul.hivemem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Domain wrapper over {@link HiveMemClient} for the research-memory cell writes/reads Dracul's
 * hunting agents need: a thesis cell after a hunt, an outcome cell once realized-R is known, and
 * a pre-fetch search for daywalker/renfield. Every method is guarded/best-effort — mirrors {@code
 * EchoEnrichmentService}'s posture that an external-lookup failure degrades one field, never the
 * whole run/hunt. A HiveMem outage, a malformed response, or a bug in the mapping code here must
 * never propagate out of this class.
 *
 * <p>Cells only: writes go exclusively through {@code add_cell} (realm {@value #REALM}) — no
 * {@code kg_add}/{@code add_tunnel}/{@code revise_cell} calls live here.
 *
 * <p>The real HiveMem {@code add_cell} tool requires {@code content} as a plain string (not a
 * nested JSON object) and returns the created cell's id under the key {@code id} (not {@code
 * cell_id}); {@code search} returns a raw JSON array of cell objects (not wrapped in a {@code
 * results} field), and its default field selection omits {@code content} unless explicitly
 * requested via {@code include}. This implementation follows the real schemas, confirmed by
 * reading HiveMem's {@code AddCellToolHandler}/{@code SearchToolHandler}/{@code
 * CellFieldSelection}/{@code WriteToolService} sources, not the illustrative sketch in the task
 * brief.
 */
@Component
public class HiveMemResearchService {

    private static final Logger log = LoggerFactory.getLogger(HiveMemResearchService.class);
    private static final String REALM = "dracul-research";

    private final HiveMemClient client;
    private final ObjectMapper mapper;

    public HiveMemResearchService(HiveMemClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** One add_cell (realm dracul-research). Returns the cell id, or empty on any degrade;
     *  never throws. */
    public Optional<String> writeThesisMemory(String kind, String symbol, String anomalyType,
            String thesis, List<String> signals, List<String> risks, List<String> killCriteria,
            String horizon, String discoveredBy, double confidence, String refId) {
        try {
            ObjectNode content = mapper.createObjectNode();
            content.put("symbol", symbol);
            content.put("anomalyType", anomalyType);
            content.put("thesis", thesis);
            content.put("confidence", confidence);
            content.set("signals", mapper.valueToTree(signals == null ? List.of() : signals));
            content.set("risks", mapper.valueToTree(risks == null ? List.of() : risks));
            content.set("killCriteria",
                    mapper.valueToTree(killCriteria == null ? List.of() : killCriteria));
            content.put("horizon", horizon);
            content.put("discoveredBy", discoveredBy);
            content.put("kind", kind);
            content.put("refId", refId);

            ObjectNode args = mapper.createObjectNode();
            args.put("realm", REALM);
            args.put("signal", "events");
            args.put("topic", symbol);
            args.put("content", mapper.writeValueAsString(content));
            args.put("summary", summaryOf(thesis));
            args.set("tags", mapper.valueToTree(List.of(symbol, anomalyType, discoveredBy, kind)));
            args.put("importance", importanceFrom(confidence));
            args.put("valid_from", Instant.now().toString());

            JsonNode res = client.callToolWrite("add_cell", args);
            String cellId = res.path("id").asText(null);
            return Optional.ofNullable(cellId);
        } catch (RuntimeException e) {
            log.warn("writeThesisMemory degraded for {} ({}): {}", symbol, kind, e.getMessage());
            return Optional.empty();
        }
    }

    /** result is derived from realizedR's sign, NOT the outcome_log triple-barrier label. Never
     *  throws. Returns {@code true} only on a confirmed successful add_cell; {@code false} on any
     *  degrade (HiveMem unavailable, mapping error, etc.). */
    public boolean writeOutcomeCell(String thesisCellId, String symbol, String anomalyType,
            BigDecimal realizedR, BigDecimal mae, BigDecimal mfe, Integer holdingDays) {
        try {
            String result = realizedR == null ? null
                    : realizedR.signum() > 0 ? "win" : realizedR.signum() < 0 ? "loss" : "scratch";

            ObjectNode content = mapper.createObjectNode();
            content.put("symbol", symbol);
            content.put("anomalyType", anomalyType);
            content.put("thesisCellId", thesisCellId);
            putNullable(content, "realizedR", realizedR);
            if (result != null) content.put("result", result); else content.putNull("result");
            putNullable(content, "mae", mae);
            putNullable(content, "mfe", mfe);
            if (holdingDays != null) content.put("holdingDays", holdingDays);
            else content.putNull("holdingDays");

            ObjectNode args = mapper.createObjectNode();
            args.put("realm", REALM);
            args.put("signal", "events");
            args.put("topic", symbol);
            args.put("content", mapper.writeValueAsString(content));
            args.put("summary", "outcome: " + (result == null ? "unresolved" : result));
            args.set("tags", mapper.valueToTree(List.of(symbol, anomalyType, "outcome")));
            args.put("valid_from", Instant.now().toString());

            client.callToolWrite("add_cell", args);
            return true;
        } catch (RuntimeException e) {
            log.warn("writeOutcomeCell degraded for {} (thesis cell {}): {}",
                    symbol, thesisCellId, e.getMessage());
            return false;
        }
    }

    /** daywalker/renfield pre-fetch only; degrades to an empty list on any failure. The
     *  per-invocation wall-clock deadline lives in the CALLER (RenfieldScheduler /
     *  DaywalkerWebhookController), not here — this method does not accept or enforce one. */
    public List<MemoryHit> searchForInput(String symbol, int limit) {
        try {
            ObjectNode where = mapper.createObjectNode();
            where.put("realm", REALM);
            where.put("topic", symbol);

            ObjectNode args = mapper.createObjectNode();
            args.set("where", where);
            args.put("limit", limit);
            // NOTE: 'include' REPLACES the search defaults (summary, tags, importance,
            // created_at) rather than adding to them (HiveMem's CellFieldSelection.from()),
            // so 'summary' must be listed explicitly alongside 'content' or it drops out of
            // the response MemoryHit needs.
            args.set("include", mapper.valueToTree(List.of("summary", "content")));

            JsonNode res = client.callToolRead("search", args);
            List<MemoryHit> hits = new ArrayList<>();
            if (res != null && res.isArray()) {
                for (JsonNode row : res) {
                    hits.add(new MemoryHit(row.path("id").asText(null),
                            row.path("summary").asText(null),
                            row.path("content").asText(null)));
                }
            }
            return hits;
        } catch (RuntimeException e) {
            log.warn("searchForInput degraded for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private static void putNullable(ObjectNode node, String field, BigDecimal value) {
        if (value != null) node.put(field, value); else node.putNull(field);
    }

    private static String summaryOf(String thesis) {
        if (thesis == null) return "";
        return thesis.length() > 120 ? thesis.substring(0, 120) : thesis;
    }

    private static int importanceFrom(double confidence) {
        if (confidence >= 0.85) return 4;
        if (confidence >= 0.65) return 3;
        if (confidence >= 0.40) return 2;
        return 1;
    }
}
