package de.visterion.dracul.hunting.agora;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * Per-symbol sector lookup (T2.2, Part B): Agora get_company_profile → finnhubIndustry —
 * the same field Echo's EquityMetricsExtractor reads. TTL-cached per symbol (24 h);
 * failures/missing profiles are negatively cached for a SHORT TTL (1 h) so a flaky Agora
 * doesn't hammer profiles every poll. Never throws; unresolved = null everywhere.
 */
@Service
public class SectorResolver {

    private static final Logger log = LoggerFactory.getLogger(SectorResolver.class);

    private final AgoraCompanyData companyData;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final TtlCache<String, String> cache = new TtlCache<>();

    public SectorResolver(AgoraCompanyData companyData,
                          @Value("${dracul.sector.ttl-seconds:86400}") long ttlSeconds,
                          @Value("${dracul.sector.negative-ttl-seconds:3600}") long negativeTtlSeconds) {
        this.companyData = companyData;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.negativeTtl = Duration.ofSeconds(negativeTtlSeconds);
    }

    /** Finnhub industry string or null (also null on any failure — never fails a poll). */
    public String sector(String symbol) {
        if (cache.contains(symbol)) return cache.get(symbol);
        String resolved = null;
        try {
            JsonNode p = companyData.profile(symbol);
            if (p != null) {
                JsonNode ind = p.path("finnhubIndustry");
                if (ind.isTextual() && !ind.asString().isBlank()) resolved = ind.asString();
            }
        } catch (RuntimeException e) {
            log.debug("sector lookup failed for {}: {}", symbol, e.getMessage());
        }
        cache.put(symbol, resolved, resolved != null ? ttl : negativeTtl);
        return resolved;
    }

    /** Cache-only read for snapshot assembly (round 1, m5) — never fetches. */
    public String cachedSector(String symbol) {
        return cache.get(symbol);
    }
}
