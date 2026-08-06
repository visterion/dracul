package de.visterion.dracul.strigoi;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-batch source-down guard for Strigoi enrichment: one instance per (hunter, source) for the
 * duration of one batch. It answers a single question — has the SOURCE failed, or has one ITEM
 * simply not been found? — and once it says "down", the caller stops querying that source for the
 * rest of the batch rather than burning the webhook's 30s budget on calls that cannot succeed.
 *
 * <p>The guard used to answer that question from the exception TYPE alone, and got it wrong in
 * production (2026-08-06, insider): {@code Agora tool error: Yahoo Finance OHLC returned HTTP 404
 * NOT_FOUND} and {@code Agora tool error: no CIK for N/A} each disabled a whole source after a
 * single cluster, and the two together crossed {@code skipAll()} — one 404 and one unresolvable
 * issuer switched enrichment off for the entire run. Both statements were about ONE item.
 *
 * <p>So the guard now trips on two kinds of evidence, and nothing else:
 * <ul>
 *   <li><b>Immediately</b>, on a {@link AgoraUnavailableException.Scope#SOURCE} failure — Agora
 *       produced no answer at all (transport, empty body, unparseable body), or a non-Agora feed
 *       reported {@link MarketDataException.Kind#UNAVAILABLE}. There is nothing to learn from a
 *       second attempt, and this is the expensive case: a dead call burns ~16s.</li>
 *   <li><b>After {@value #MAX_CONSECUTIVE_REQUEST_FAILURES} consecutive</b>
 *       {@link AgoraUnavailableException.Scope#REQUEST} failures with no success in between.
 *       Agora answering "error" for every single item in a row is not a story about the items.</li>
 * </ul>
 *
 * <p>Note what does NOT touch the counter: a {@link MarketDataException.Kind#NOT_FOUND}, or any
 * unrelated {@code RuntimeException}. Those are benign per-item outcomes — the same shape of
 * decision {@code LazarusUniverseService} makes for a listing too young to have a 52-week range:
 * neither incremented (benign outcomes cluster, and would abort the pass) nor reset (that would
 * hide a genuine outage running through them).
 *
 * <p>No message-substring matching anywhere: the scope is recorded at the throw site in
 * {@code AgoraClient}, where the fact is known. This codebase has twice been bitten by inferring
 * meaning from provider prose, and Yahoo's and EDGAR's wording is not a contract.
 */
public final class EnrichmentSourceGuard {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentSourceGuard.class);

    /**
     * Consecutive REQUEST-scoped failures, with no success in between, that count as an outage.
     *
     * <p>Three, and the number is bounded from both sides. Below: two is what production actually
     * produced from healthy-but-unknown items, and adjacent unresolvable tickers are ordinary in a
     * market-wide Form-4 window (foreign and OTC issuers arrive in runs). Above: the enrichment
     * batches this guard serves are capped at 25–30 items, so Lazarus's threshold of 10 — set for a
     * pass over hundreds of index members — would make the guard dead code here, never reached
     * inside a single batch. Three is also cheap to be wrong about in the direction that matters:
     * a REQUEST failure means Agora ANSWERED, so the two extra attempts cost a round trip each,
     * not the ~16s of a dead call, which is the case the immediate SOURCE trip already covers.
     */
    static final int MAX_CONSECUTIVE_REQUEST_FAILURES = 3;

    private final String hunter;
    private final String remainingNoun;
    private final String source;

    private int consecutiveRequestFailures;
    private boolean down;

    private EnrichmentSourceGuard(String hunter, String remainingNoun, String source) {
        this.hunter = hunter;
        this.remainingNoun = remainingNoun;
        this.source = source;
    }

    /** @param hunter        the Strigoi name for the log line (e.g. {@code "lazarus"})
     *  @param remainingNoun the batch unit skipped for the rest of the run (e.g. {@code "clusters"})
     *  @param source        the source label for the log line (e.g. {@code "ohlc history"}) */
    public static EnrichmentSourceGuard forSource(String hunter, String remainingNoun, String source) {
        return new EnrichmentSourceGuard(hunter, remainingNoun, source);
    }

    /** True once this source has been declared down for the rest of the batch. */
    public boolean isDown() { return down; }

    /** A successful lookup: the source demonstrably answers, so the run of failures is broken. */
    public void recordSuccess() {
        consecutiveRequestFailures = 0;
    }

    /** Records one failed lookup.
     *
     *  @return {@code true} when the source is (now) down and must not be queried again this batch.
     *          {@code false} means this was a per-item failure: the caller degrades that one item's
     *          fields and carries on with the next. */
    public boolean recordFailure(RuntimeException e) {
        if (down) return true;

        AgoraUnavailableException agora = AgoraUnavailableException.unwrap(e);
        boolean requestScoped = agora != null && agora.scope() == AgoraUnavailableException.Scope.REQUEST;
        // A MarketDataException(UNAVAILABLE) that wraps an Agora failure inherits that failure's
        // scope, not the wrapper's kind — the OHLC path always re-wraps, and reading the wrapper
        // is exactly how a Yahoo 404 came to be logged as an outage.
        boolean availabilityFailure = agora != null
                || (e instanceof MarketDataException m && m.kind() == MarketDataException.Kind.UNAVAILABLE);
        if (!availabilityFailure) {
            return false; // NOT_FOUND or an unrelated bug: says nothing about the source
        }

        if (!requestScoped) {
            down = true;
            log.warn("{} enrichment: {} source down ({}), skipping it for the remaining {}",
                    hunter, source, e.getMessage(), remainingNoun);
            return true;
        }

        consecutiveRequestFailures++;
        if (consecutiveRequestFailures < MAX_CONSECUTIVE_REQUEST_FAILURES) {
            log.debug("{} enrichment: {} returned a per-item error ({}), failure {} of {} in a row",
                    hunter, source, e.getMessage(), consecutiveRequestFailures,
                    MAX_CONSECUTIVE_REQUEST_FAILURES);
            return false;
        }
        down = true;
        log.warn("{} enrichment: {} source down ({} consecutive per-item errors, last: {}), "
                        + "skipping it for the remaining {}",
                hunter, source, consecutiveRequestFailures, e.getMessage(), remainingNoun);
        return true;
    }
}
