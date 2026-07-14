package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.FxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Assembles the GUI's depot read path: lists Agora's configured broker connections, gates
 * live-environment connections behind an allow-listed set of user emails, fetches
 * account/positions/orders per connection (isolating failures to that connection), enriches
 * positions with a single batched {@code get_quote} call, and derives depot-level aggregates.
 *
 * <p>All monetary/percentage output is {@link BigDecimal} at scale 2, {@link RoundingMode#HALF_UP}.
 * Any ratio whose denominator would be zero (or missing) is left {@code null} rather than
 * dividing by zero.
 */
@Service
public class DepotService {

    private static final Logger log = LoggerFactory.getLogger(DepotService.class);
    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AgoraDepotClient depotClient;
    private final AgoraClient agora;
    private final FxService fx;
    private final Set<String> liveVisibleEmails;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int cacheTtlSeconds;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Overridable in tests to simulate TTL expiry without real sleeps. */
    LongSupplier nowMillis = System::currentTimeMillis;

    private record CacheEntry(DepotDto dto, long expiresAtMillis) {}

    public DepotService(AgoraDepotClient depotClient, AgoraClient agora, FxService fx,
            @Value("${dracul.depots.live-visible-emails:viktor@ufelmann.de}") String liveEmailsCsv,
            @Value("${dracul.depots.cache-ttl-seconds:60}") int cacheTtlSeconds) {
        this.depotClient = depotClient;
        this.agora = agora;
        this.fx = fx;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.liveVisibleEmails = new HashSet<>();
        if (liveEmailsCsv != null) {
            for (String email : liveEmailsCsv.split(",")) {
                String trimmed = email.trim();
                if (!trimmed.isEmpty()) liveVisibleEmails.add(trimmed.toLowerCase());
            }
        }
    }

    public List<DepotDto> depots(String userEmail) {
        return depots(userEmail, false);
    }

    public List<DepotDto> depots(String userEmail, boolean forceRefresh) {
        List<DepotConnection> visible = visibleConnections(userEmail);
        if (visible.isEmpty()) return List.of();

        List<DepotDto> result = new ArrayList<>();
        for (DepotConnection c : visible) {
            result.add(cachedOrFresh(c, forceRefresh));
        }
        return result;
    }

    public DepotDto depot(String connection, String userEmail, boolean forceRefresh) {
        List<DepotConnection> visible = visibleConnections(userEmail);
        DepotConnection match = visible.stream().filter(c -> connection.equals(c.id())).findFirst().orElse(null);
        if (match == null) return null;
        return cachedOrFresh(match, forceRefresh);
    }

    private List<DepotConnection> visibleConnections(String userEmail) {
        List<DepotConnection> connections;
        try {
            connections = depotClient.listConnections();
        } catch (DepotUnavailableException e) {
            log.warn("listConnections failed: {}", e.toString());
            throw e;
        } catch (RuntimeException e) {
            log.warn("listConnections failed: {}", e.toString());
            throw new DepotUnavailableException(e.getMessage(), e);
        }

        List<DepotConnection> visible = new ArrayList<>();
        for (DepotConnection c : connections) {
            if (isLiveVisible(c, userEmail)) visible.add(c);
        }
        return visible;
    }

    /** Cache read-through with per-connection single-flight: concurrent misses for the same
     *  connection do not both hit Saxo. Error DTOs are never stored (always retried). */
    private DepotDto cachedOrFresh(DepotConnection c, boolean forceRefresh) {
        long now = nowMillis.getAsLong();
        if (!forceRefresh) {
            CacheEntry entry = cache.get(c.id());
            if (entry != null && entry.expiresAtMillis() > now) return entry.dto();
        }

        Object lock = locks.computeIfAbsent(c.id(), k -> new Object());
        synchronized (lock) {
            long recheckNow = nowMillis.getAsLong();
            if (!forceRefresh) {
                CacheEntry entry = cache.get(c.id());
                if (entry != null && entry.expiresAtMillis() > recheckNow) return entry.dto();
            }
            DepotDto dto = assembleOne(c);
            if (dto.error() == null) {
                cache.put(c.id(), new CacheEntry(dto, recheckNow + cacheTtlSeconds * 1000L));
            } else {
                cache.remove(c.id());
            }
            return dto;
        }
    }

    /** Fetches account/positions/orders for one connection, isolating failure into an error DTO
     *  (matching the historical per-connection isolation behavior of {@code depots}). */
    private DepotDto assembleOne(DepotConnection c) {
        DepotAccount account;
        PositionsSnapshot positions;
        List<DepotOrder> orders;
        try {
            account = depotClient.account(c.id());
            positions = depotClient.positions(c.id());
            orders = depotClient.orders(c.id());
        } catch (RuntimeException e) {
            return new DepotDto(c.id(), c.provider(), c.environment(), c.status(), c.probedAt(),
                    e.getMessage(), null, null, null, null, null);
        }

        Set<String> symbols = new HashSet<>();
        for (DepotPosition p : positions.positions()) {
            if (p.symbol() != null) symbols.add(p.symbol());
        }
        Map<String, QuoteData> quotes = fetchQuotes(symbols);

        return assemble(c, account, positions, orders, quotes);
    }

    private boolean isLiveVisible(DepotConnection c, String userEmail) {
        boolean isLive = "live".equalsIgnoreCase(c.environment());
        if (!isLive) return true;
        if (userEmail == null) return false;
        return liveVisibleEmails.contains(userEmail.toLowerCase());
    }

    private record QuoteData(BigDecimal price, BigDecimal dayChangePercent) {}

    private Map<String, QuoteData> fetchQuotes(Set<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        try {
            ObjectNode args = mapper.createObjectNode();
            var arr = args.putArray("symbols");
            for (String s : symbols) arr.add(s);
            JsonNode out = agora.callTool("get_quote", args);

            Map<String, QuoteData> byUnwrapped = new HashMap<>();
            JsonNode quotesArr = out.path("quotes");
            if (quotesArr.isArray()) {
                for (JsonNode q : quotesArr) {
                    String symbol = textOrNull(q, "symbol");
                    if (symbol == null) continue;
                    try {
                        byUnwrapped.put(symbol, new QuoteData(
                                decimalOrNull(q, "price"), decimalOrNull(q, "dayChangePercent")));
                    } catch (NumberFormatException e) {
                        log.warn("Malformed quote data for {}: {}", symbol, e.toString());
                        byUnwrapped.put(symbol, new QuoteData(null, null));
                    }
                }
            }
            return byUnwrapped;
        } catch (AgoraUnavailableException e) {
            log.warn("get_quote failed: {}", e.toString());
            return Map.of();
        }
    }

    private DepotDto assemble(DepotConnection c, DepotAccount account, PositionsSnapshot positions,
            List<DepotOrder> orders, Map<String, QuoteData> quotes) {

        String acctCcy = account != null ? account.currency() : null;

        BigDecimal investedValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPl = BigDecimal.ZERO;
        BigDecimal dayChangeAbs = BigDecimal.ZERO;
        boolean anyQuote = false;

        for (DepotPosition p : positions.positions()) {
            BigDecimal mv = fx.convert(p.marketValue(), p.currency(), acctCcy);
            BigDecimal upl = fx.convert(p.unrealizedPl(), p.currency(), acctCcy);

            if (mv != null) investedValue = investedValue.add(mv);
            if (upl != null) totalUnrealizedPl = totalUnrealizedPl.add(upl);

            QuoteData q = quotes.get(p.symbol());
            if (q != null && q.dayChangePercent() != null && mv != null) {
                anyQuote = true;
                dayChangeAbs = dayChangeAbs.add(
                        mv.multiply(q.dayChangePercent()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            }
        }

        BigDecimal investedValueScaled = investedValue.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal totalUnrealizedPlScaled = totalUnrealizedPl.setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal totalUnrealizedPlPct = percentOf(totalUnrealizedPl, investedValue.subtract(totalUnrealizedPl));
        BigDecimal dayChangeAbsScaled = anyQuote ? dayChangeAbs.setScale(SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal dayChangePct = anyQuote ? percentOf(dayChangeAbs, investedValue) : null;

        DepotAggregates aggregates = new DepotAggregates(
                investedValueScaled, totalUnrealizedPlScaled, totalUnrealizedPlPct,
                dayChangeAbsScaled, dayChangePct);

        List<DepotPositionDto> positionDtos = new ArrayList<>();
        for (DepotPosition p : positions.positions()) {
            QuoteData q = quotes.get(p.symbol());
            BigDecimal price = q == null ? null : q.price();
            BigDecimal dayChangePercent = q == null ? null : q.dayChangePercent();

            // Native (pre-conversion) price + currency, for the "€ (191,13 $)" display. Null when
            // the position is already in the account currency, so MoneyDisplay shows no parens.
            boolean nativeDiffersFromAccount = p.currency() != null && acctCcy != null
                    && !p.currency().equalsIgnoreCase(acctCcy);
            BigDecimal nativePrice = nativeDiffersFromAccount && price != null
                    ? price.setScale(SCALE, RoundingMode.HALF_UP) : null;
            String nativeCurrency = nativeDiffersFromAccount ? p.currency() : null;

            BigDecimal mv = fx.convert(p.marketValue(), p.currency(), acctCcy);
            BigDecimal upl = fx.convert(p.unrealizedPl(), p.currency(), acctCcy);
            BigDecimal convertedPrice = fx.convert(price, p.currency(), acctCcy);
            BigDecimal avgEntryPrice = fx.convert(p.avgEntryPrice(), p.currency(), acctCcy);

            BigDecimal mvScaled = mv != null ? mv.setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal uplScaled = upl != null ? upl.setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal priceScaled = convertedPrice != null ? convertedPrice.setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal avgEntryPriceScaled = avgEntryPrice != null ? avgEntryPrice.setScale(SCALE, RoundingMode.HALF_UP) : null;

            BigDecimal costBasis = (p.qty() != null && avgEntryPrice != null)
                    ? p.qty().multiply(avgEntryPrice) : null;
            BigDecimal unrealizedPlPct = percentOf(upl, costBasis);
            BigDecimal weightPct = percentOf(mv, investedValue);

            positionDtos.add(new DepotPositionDto(p.symbol(), p.qty(), avgEntryPriceScaled,
                    mvScaled, uplScaled, unrealizedPlPct, priceScaled, dayChangePercent,
                    weightPct, acctCcy,
                    p.description(), p.assetType(), p.valueDate(),
                    nativePrice, nativeCurrency));
        }

        String asOf = positions.asOf() != null ? positions.asOf()
                : (account != null ? account.asOf() : null);

        return new DepotDto(c.id(), c.provider(), c.environment(), c.status(), c.probedAt(), null,
                account, aggregates, positionDtos, orders, asOf);
    }

    /** numerator / denominator * 100, scale 2 HALF_UP; null if either operand is null or denominator is zero. */
    private BigDecimal percentOf(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) return null;
        return numerator.divide(denominator, 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        return new BigDecimal(v.asString());
    }
}
