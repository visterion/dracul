package de.visterion.dracul.hunting.agora;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * The one shared sector vocabulary (spec T3.3 D5): resolution cascade
 * {@code sector → finnhubIndustry → gicsSector} over the Agora company profile, extracted
 * from EntryContextAssembler's previously inline (and uncached) lookup. TTL-cached per
 * symbol with SectorResolver's policy — positive 24 h, negative 1 h — so the
 * fetch-elapsed-prey enrichment cannot fire up to 25 cold profile calls inside the 30 s
 * webhook window. Consumers: EntryContextAssembler (veto path), PatternOutcomeScorer
 * (fallback when the position row has no sector), VoievodOutcomeController (fetch wire
 * enrichment). SectorResolver's single-field read stays for daywalker/renfield.
 * Never throws; unresolved = null.
 */
@Service
public class SectorCascade {

    private static final Logger log = LoggerFactory.getLogger(SectorCascade.class);

    private final AgoraCompanyData companyData;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final TtlCache<String, String> cache = new TtlCache<>();

    public SectorCascade(AgoraCompanyData companyData,
            @Value("${dracul.sector.ttl-seconds:86400}") long ttlSeconds,
            @Value("${dracul.sector.negative-ttl-seconds:3600}") long negativeTtlSeconds) {
        this.companyData = companyData;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.negativeTtl = Duration.ofSeconds(negativeTtlSeconds);
    }

    /** Cascade sector string or null; cached; never throws. */
    public String resolve(String symbol) {
        if (cache.contains(symbol)) return cache.get(symbol);
        String resolved = null;
        try {
            JsonNode profile = companyData.profile(symbol);
            if (profile != null) {
                resolved = firstNonBlank(
                        profile.path("sector").asString(""),
                        profile.path("finnhubIndustry").asString(""),
                        profile.path("gicsSector").asString(""));
            }
        } catch (RuntimeException e) {
            log.debug("sector cascade lookup failed for {}: {}", symbol, e.getMessage());
        }
        cache.put(symbol, resolved, resolved != null ? ttl : negativeTtl);
        return resolved;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
