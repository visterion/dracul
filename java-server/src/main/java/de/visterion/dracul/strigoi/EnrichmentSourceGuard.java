package de.visterion.dracul.strigoi;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared source-down guard for per-batch Strigoi enrichment. Returns {@code true} (and WARNs
 *  once) when the exception means the source is <em>unavailable</em> — its backend is down, so
 *  the caller should skip that source for the rest of the batch rather than burn the webhook's
 *  latency budget retrying it. Returns {@code false} for symbol-specific failures (e.g.
 *  NOT_FOUND), which the caller handles/propagates normally. */
public final class EnrichmentSourceGuard {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentSourceGuard.class);

    private EnrichmentSourceGuard() {}

    /** @param e            the failure raised by the source lookup
     *  @param hunter       the Strigoi name for the log line (e.g. {@code "lazarus"})
     *  @param remainingNoun the batch unit skipped for the rest of the run (e.g. {@code "candidates"} or {@code "clusters"})
     *  @param source       the source label for the log line (e.g. {@code "ohlc history"})
     *  @return {@code true} (and WARNs) when the failure is an availability failure
     *          ({@link AgoraUnavailableException} or {@link MarketDataException} of kind
     *          UNAVAILABLE), {@code false} otherwise. */
    public static boolean isSourceDown(RuntimeException e, String hunter, String remainingNoun, String source) {
        boolean availabilityFailure = e instanceof AgoraUnavailableException
                || (e instanceof MarketDataException m && m.kind() == MarketDataException.Kind.UNAVAILABLE);
        if (availabilityFailure) {
            log.warn("{} enrichment: {} source down ({}), skipping it for the remaining {}",
                    hunter, source, e.getMessage(), remainingNoun);
        }
        return availabilityFailure;
    }
}
