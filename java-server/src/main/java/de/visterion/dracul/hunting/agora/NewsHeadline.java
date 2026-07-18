package de.visterion.dracul.hunting.agora;

import java.time.Instant;

/**
 * One company-news item from Agora's get_company_news. {@code sourceType} is a media-type
 * label ("news" | "social"); items from an older Agora without the field default to "news".
 * {@code domain} is Agora's derived lowercase url host (null for old-Agora payloads and
 * unparsable urls); {@code credibility} is the 0-1 operator-table score (T1.4) — always
 * set, sub-threshold items never leave the AgoraCompanyData chokepoint.
 */
public record NewsHeadline(String headline, String summary, String source,
                           String sourceType, Instant datetime, String url,
                           String domain, double credibility) {

    /** Convenience for pre-T1.4 call sites (tagger/detector test fixtures): no domain,
     *  neutral 0.5. The chokepoint always uses the canonical constructor. */
    public NewsHeadline(String headline, String summary, String source,
                        String sourceType, Instant datetime, String url) {
        this(headline, summary, source, sourceType, datetime, url, null, 0.5);
    }
}
