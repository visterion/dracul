package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.EnrichmentSourceGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves which listing each screened candidate's fundamentals describe, per
 * {@link ListingResolution}'s contract. The screener cannot do this itself (pure, I/O-free) and
 * always leaves candidates at {@link ListingResolution#UNKNOWN}; this is the one place that
 * calls out to {@code get_company_profile} to upgrade that state.
 *
 * <p>Resolution order per candidate:
 * <ol>
 *   <li>{@code reportingCurrency() != null} — already {@link ListingResolution#FOREIGN_SUFFIXED},
 *       currency-agnostic, no remote call needed.</li>
 *   <li>Otherwise a profile lookup decides: {@code ticker} matching the symbol (case-insensitive)
 *       is {@link ListingResolution#US_CONFIRMED}; a present-but-different ticker means the
 *       candidate's numbers describe a different listing and it is DROPPED; a missing/blank
 *       ticker, a missing profile, or the guard/cap already being tripped all leave the
 *       candidate at {@link ListingResolution#UNKNOWN} — deliberately never
 *       {@code US_CONFIRMED}, since absence of evidence is not evidence of a US listing.</li>
 * </ol>
 *
 * <p>The profile lookup sits behind an {@link EnrichmentSourceGuard}, exactly like the other
 * Agora-backed enrichment sources ({@code LazarusEnrichmentService}): a single per-request error
 * (an unknown symbol, an unresolvable issuer) says nothing about Agora's health and must not
 * disable the source for the rest of the batch — reading a lone REQUEST-scoped failure as an
 * outage is what took down a whole insider enrichment run on 2026-08-06. Only a
 * {@code Scope.SOURCE} failure (no answer at all) or a run of consecutive REQUEST failures trips
 * the guard, after which remaining candidates are left {@code UNKNOWN} without further calls.
 * {@code profileMax} additionally bounds the number of profile calls per batch regardless of the
 * guard, so a healthy-but-large batch cannot burn the webhook's time budget one profile call at
 * a time.
 */
@Component
public class LazarusListingResolver {

    private static final Logger log = LoggerFactory.getLogger(LazarusListingResolver.class);

    private final AgoraCompanyData companyData;
    private final int profileMax;

    public LazarusListingResolver(AgoraCompanyData companyData,
            @Value("${dracul.strigoi.lazarus.profile-max:40}") int profileMax) {
        this.companyData = companyData;
        this.profileMax = profileMax;
    }

    /** {@code candidates} carry a resolved {@link ListingResolution}; foreign listings (a
     *  profile whose ticker names a different listing) are removed rather than kept with a
     *  flag, since their fundamentals describe the wrong instrument outright. {@code
     *  foreignListing} and {@code listingUnknown} count the two different reasons a candidate's
     *  size cannot be trusted — never sharing a counter, since one is a permanent instrument
     *  property and the other is a lookup failure. */
    public record Resolved(List<LazarusCandidate> candidates, int foreignListing, int listingUnknown) {}

    public Resolved resolve(List<LazarusCandidate> candidates) {
        var guard = EnrichmentSourceGuard.forSource("lazarus", "candidates", "company profile");
        List<LazarusCandidate> out = new ArrayList<>();
        int foreignListing = 0;
        int listingUnknown = 0;
        int calls = 0;

        for (LazarusCandidate c : candidates) {
            // A blank (non-null) currency carries no evidence, exactly like a blank ticker below —
            // it must not count as "present" and fall straight to FOREIGN_SUFFIXED. It falls through
            // to the same profile resolution a null currency gets.
            if (c.reportingCurrency() != null && !c.reportingCurrency().isBlank()) {
                out.add(c.withListing(ListingResolution.FOREIGN_SUFFIXED));
                continue;
            }

            if (guard.isDown() || calls >= profileMax) {
                out.add(c.withListing(ListingResolution.UNKNOWN));
                if (c.marketCap() != null) listingUnknown++;
                continue;
            }

            calls++;
            JsonNode profile;
            try {
                profile = companyData.profileStrict(c.symbol());
                guard.recordSuccess();
            } catch (RuntimeException e) {
                guard.recordFailure(e);
                // AgoraUnavailableException.unwrap == null means this was neither a SOURCE nor a
                // REQUEST-scoped Agora failure — an unrelated bug (e.g. a malformed profile blob
                // throwing while it is read), not an availability story. recordFailure(e) above
                // treats it as a benign per-item outcome (same as EnrichmentSourceGuard's own
                // NOT_FOUND/unrelated-RuntimeException carve-out) and never trips the guard for it,
                // so DEBUG would be its only trace — invisible in a nightly run. WARN instead; the
                // Agora outage path (SOURCE/REQUEST) keeps its existing DEBUG line unchanged.
                if (AgoraUnavailableException.unwrap(e) == null) {
                    log.warn("lazarus listing resolution: {} threw a non-availability error resolving its listing: {}",
                            c.symbol(), e.getMessage(), e);
                } else {
                    log.debug("lazarus listing resolution: profile unavailable for {}: {}",
                            c.symbol(), e.getMessage());
                }
                out.add(c.withListing(ListingResolution.UNKNOWN));
                if (c.marketCap() != null) listingUnknown++;
                continue;
            }

            String ticker = profile == null ? "" : profile.path("ticker").asString("");
            if (ticker.isBlank()) {
                out.add(c.withListing(ListingResolution.UNKNOWN));
                if (c.marketCap() != null) listingUnknown++;
            } else if (ticker.equalsIgnoreCase(c.symbol())) {
                out.add(c.withListing(ListingResolution.US_CONFIRMED));
            } else {
                foreignListing++;
                log.debug("lazarus listing resolution: {} dropped, profile ticker {} names a different listing",
                        c.symbol(), ticker);
            }
        }

        return new Resolved(List.copyOf(out), foreignListing, listingUnknown);
    }
}
