package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.DataSourceHealth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression anchor for the merger hunter's tool-result size against the Claude-Max bridge's
 * REAL limit — the one that blinded this hunter completely.
 *
 * <p><b>The limit, located rather than assumed.</b> Every Vistierie tool (Dracul's HTTP webhooks
 * included) is handed to the model as an in-process SDK MCP tool: {@code claude-bridge}'s
 * {@code buildTool()} returns {@code {content:[{type:"text",text}]}}, so the Claude Code CLI's
 * MCP output cap applies. In the CLI binary shipped with
 * {@code @anthropic-ai/claude-agent-sdk-linux-x64} the cap is {@code MAX_MCP_OUTPUT_TOKENS}
 * (unset on the bridge container, so the compiled default applies) = <b>25 000 tokens</b>, and it
 * is enforced in two steps:
 * <ol>
 *   <li>a cheap pre-check estimates tokens as {@code round(chars / 4)} and returns "no
 *       truncation" outright when that estimate is {@code <= 25 000 * 0.5 = 12 500} tokens —
 *       i.e. anything at or below <b>50 000 characters</b> is provably never touched;</li>
 *   <li>above that the real tokenizer runs, and a result over 25 000 tokens is cut to
 *       {@code MAX_MCP_OUTPUT_TOKENS * 4 = 100 000 characters} with
 *       {@code [OUTPUT TRUNCATED - exceeded 25000 token limit]} appended.</li>
 * </ol>
 * So {@code ~95 kB} was folklore: the hard ceiling is 100 000 chars and the <em>guaranteed-safe</em>
 * zone is 50 000 chars. {@link #BUDGET_CHARS} targets the safe zone deliberately — below it the
 * truncation check does not even run, which is a stronger guarantee than "probably under 25 000
 * tokens" for content as token-dense as JSON.
 *
 * <p><b>What actually happened.</b> Measured on production run {@code 74754073…} (2026-08-04
 * 05:00): 25 candidates, tool payload <b>329 818 chars</b> — 3.3x the hard ceiling. Four further
 * runs sat at 305 587–353 968 chars. Each was cut mid-JSON at 100 000 chars, so the model
 * received a syntactically broken candidate list; every one of those runs finished
 * {@code status=done} with a final {@code {"prey": []}}. A dead hunter that reads as a quiet
 * market.
 *
 * <p><b>Where the bytes were.</b> Per candidate (200 records across three prod runs):
 * structured fields, JSON-encoded, avg <b>623</b> / max <b>645</b> chars; {@code termSheet} raw
 * avg <b>13 010</b> / max <b>24 000</b> (Agora's own per-filing cap, which Dracul did not reduce
 * further). The term sheet was 95 % of the payload — and the head of it is boilerplate: page
 * references, "The Parties to the Merger", incorporation blurbs. The model was being charged
 * 24 000 chars to be told where the registered office is, and then shown none of it.
 *
 * <p><b>The fix this test pins.</b> {@link TermSheetDigest} replaces the raw term sheet with a
 * bounded digest of the sections that actually price deal risk (closing conditions, regulatory
 * approvals, termination fees, solicitation, financing, vote). Everything quantitative the model
 * used to mine out of the prose — offer price, consideration type, exchange ratio, break fee,
 * agreement/close/outside dates — is already extracted server-side by {@link DealTermsParser}
 * and rides the payload as structured fields, so the digest only has to carry what parsing
 * cannot.
 *
 * <p><b>The arithmetic, which is what sets the cap.</b>
 * <pre>
 *   budget                                        50 000 chars   (the CLI's short-circuit)
 *   - envelope reserve                           -  5 000 chars   (health block, active_patterns,
 *                                                                  JSON wrapper)
 *   = available for candidates                     45 000 chars
 *   per candidate: structured (max, measured)         645 chars
 *                + "termSheetDigest" key overhead      20 chars
 *                + digest 700 raw x 1.05 escaping      735 chars
 *                = 1 400 chars
 *   cap = 45 000 / 1 400                          = 32.1  ->  30
 *   worst case = 30 x 1 400 + 5 000               = 47 000 chars
 * </pre>
 * 47 000 chars is 6 % under the 50 000-char safe zone and <b>2.13x</b> under the 100 000-char
 * hard ceiling — against 329 818 chars today, a 7x reduction. The 1.05 escaping factor is
 * measured, not guessed: across the same 200 prod records the JSON-encoded term sheet was
 * 1.0098x its raw length on average and 1.025x at the maximum.
 *
 * <p>Note the cap MOVED, in both directions, and for a reason: 25 was binding (a 45-day and a
 * 90-day window both returned exactly 25 rows), so it had to rise; 40 — the value the payload
 * fix was originally paired with — cannot fit under any budget, since 40 x 645 chars of
 * structured fields alone is 25 800 chars before a single character of deal text. 30 is what the
 * arithmetic allows.
 *
 * <p>{@link #MAX_CANDIDATES} and {@link #DIGEST_CHARS} are read from the {@code application.yaml}
 * defaults at test time rather than duplicated, so raising a cap in the YAML raises the worst
 * case checked here instead of silently invalidating it. As with
 * {@code EchoPayloadBudgetTest}, this does NOT cover a deploy-time env override
 * ({@code MERGER_MAX_CANDIDATES}), which is invisible to a repo-level test.
 */
class MergerPayloadBudgetTest {

    /**
     * The Claude Code CLI's MCP truncation pre-check short-circuits at
     * {@code MAX_MCP_OUTPUT_TOKENS * 0.5} estimated tokens, estimated as {@code chars / 4} —
     * 12 500 tokens, i.e. 50 000 characters. At or below this size the payload is provably
     * never truncated, whatever the real tokenizer would have counted.
     */
    private static final int BUDGET_CHARS = 50_000;

    /** Bound to the YAML default so the two cannot drift. */
    private static final int MAX_CANDIDATES =
            yamlDefault("(?m)^\\s{4}merger:$[\\s\\S]*?^\\s+max-candidates:\\s*\\$\\{[^:}]+:(\\d+)\\}");
    /** Bound to the YAML default so the two cannot drift. */
    private static final int DIGEST_CHARS =
            yamlDefault("(?m)^\\s{4}merger:$[\\s\\S]*?^\\s+term-sheet-digest-chars:\\s*\\$\\{[^:}]+:(\\d+)\\}");

    /**
     * Reserve for everything in the tool envelope that is NOT a candidate: the
     * {@code data_source_health} block (~250 chars including a long degradation detail) and
     * {@code active_patterns}, which {@code PatternRepository.findAcceptedByStrigoi} feeds into
     * every hunter's response UNCAPPED. The reserve is the only thing standing between that
     * uncapped list and this budget; it is deliberately generous.
     */
    private static final int ENVELOPE_RESERVE_CHARS = 5_000;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void worstCaseFetchPayloadStaysInsideTheBridgesSafeZone() {
        String payload = mapper.writeValueAsString(worstCasePayload());
        int chars = payload.length();

        assertThat(chars)
                .as("merger fetch payload is %d chars for %d candidates (%d-char digests); "
                                + "budget is %d, i.e. %.1f%% over. The Claude-Max bridge cuts an MCP "
                                + "tool result at 100 000 chars and only provably leaves it alone "
                                + "below 50 000 — a payload past this is read by nobody. Lower "
                                + "dracul.strigoi.merger.max-candidates or "
                                + "dracul.strigoi.merger.term-sheet-digest-chars.",
                        chars, MAX_CANDIDATES, DIGEST_CHARS, BUDGET_CHARS,
                        100.0 * (chars - BUDGET_CHARS) / BUDGET_CHARS)
                .isLessThanOrEqualTo(BUDGET_CHARS);
    }

    /**
     * The cap and the digest size are not independent knobs — this is the inequality that ties
     * them together, checked in the abstract so that raising one without lowering the other fails
     * with the arithmetic rather than with a byte count.
     */
    @Test
    void capAndDigestSizeSatisfyTheDerivation() {
        int structuredMax = 645;   // measured, 200 prod candidate records
        int keyOverhead = 20;
        int perCandidate = structuredMax + keyOverhead + (int) Math.ceil(DIGEST_CHARS * 1.05);
        int worstCase = MAX_CANDIDATES * perCandidate + ENVELOPE_RESERVE_CHARS;

        assertThat(worstCase)
                .as("cap %d x per-candidate %d + envelope %d = %d chars, over the %d budget",
                        MAX_CANDIDATES, perCandidate, ENVELOPE_RESERVE_CHARS, worstCase, BUDGET_CHARS)
                .isLessThanOrEqualTo(BUDGET_CHARS);
    }

    /** Nothing may put the raw term sheet back on the wire: that is the whole regression. */
    @Test
    void theRawTermSheetIsNotOnTheWire() {
        String raw = "CONDITIONS TO THE MERGER\n" + "x".repeat(24_000);
        var filings = org.mockito.Mockito.mock(de.visterion.dracul.hunting.agora.AgoraFilings.class);
        var md = org.mockito.Mockito.mock(de.visterion.dracul.marketdata.AgoraMarketData.class);
        org.mockito.Mockito.when(filings.filingText(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new de.visterion.dracul.hunting.agora.FilingText(raw, true));
        org.mockito.Mockito.when(md.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        var svc = new MergerEnrichmentService(filings, md, new DealTermsParser(),
                MAX_CANDIDATES, DIGEST_CHARS);

        var batch = svc.enrich(List.of(new MergerCandidate(
                "ACME", "Acme Corp", "DEFM14A", "2026-08-03", "https://example.com/a.htm")));

        String wire = mapper.writeValueAsString(batch.candidates());
        assertThat(wire.length())
                .as("one candidate serialized to %d chars — the raw 24 000-char term sheet is "
                        + "back on the wire", wire.length())
                .isLessThan(2_000);
    }

    // ------------------------------------------------------------------
    // worst-case fixture
    // ------------------------------------------------------------------

    private Map<String, Object> worstCasePayload() {
        List<EnrichedMergerCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            candidates.add(new EnrichedMergerCandidate(
                    "ABCDE",
                    // Longest company name seen in the prod sample, padded — the structured block
                    // measured at 645 chars max is what this reproduces.
                    "A Very Long Portfolio Company Holdings Incorporated  (ABCDE)",
                    "DEFM14A",
                    "2026-08-03",
                    // Real EDGAR archive URLs run ~100 chars; pad to the observed maximum.
                    "https://www.sec.gov/Archives/edgar/data/1974640/000114036126028341/"
                            + "ny20076262x2_defm14a_supplement_no_2.htm",
                    "d".repeat(DIGEST_CHARS),
                    true,
                    new BigDecimal("1234.567890"), true,
                    new BigDecimal("1234.567890"), "mixed", "0.123456789 shares",
                    "$1,234,567,890.00", new BigDecimal("-1234.56"),
                    LocalDate.of(2026, 6, 18), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 6, 30),
                    new BigDecimal("1234.567890"), true, -1234,
                    new BigDecimal("-12345.67"), new BigDecimal("-1234.56")));
        }

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "healthy");
        health.put("source", "agora");
        health.put("detail", "candidate list capped at dracul.strigoi.merger.max-candidates "
                + "(newest filings kept, oldest deals dropped); partial: 30 term sheet(s) could "
                + "not be fetched, so their deal terms are unparsed (12 of them exceed Agora's "
                + "filing-size cap)");
        health.put("checked_at", "2026-08-04T05:00:22.466844Z");
        health.put("partial", true);
        health.put("truncated", true);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("candidates", candidates);
        output.put("data_source_health", health);
        return Map.of("output", output);
    }

    private static int yamlDefault(String regex) {
        try (InputStream in = MergerPayloadBudgetTest.class
                .getResourceAsStream("/application.yaml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(regex).matcher(yaml);
            if (!m.find()) {
                throw new IllegalStateException("application.yaml no longer matches " + regex
                        + " — this test's binding to the YAML default has silently broken");
            }
            return Integer.parseInt(m.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
