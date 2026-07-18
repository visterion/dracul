package de.visterion.dracul.renfield;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.agora.SectorResolver;
import de.visterion.dracul.hunting.news.NewsEventTagger;
import de.visterion.dracul.hunting.news.NewsEventType;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.position.PortfolioWeights;
import de.visterion.dracul.position.PositionMath;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Daily pre-market watchlist review trigger (D4/D6): assembles the full input for the
 * renfield agent — the LLM gets facts, not tool access — and fires an on-demand
 * Vistierie run WITH the completion webhook (R3; a triggered run does not fall back to
 * the registered webhook). Never throws out of the scheduled method; an unreachable
 * Vistierie is a WARN and the next day's cron retries naturally.
 */
@Component
@ConditionalOnProperty(value = "dracul.renfield.enabled", havingValue = "true")
public class RenfieldScheduler {

    private static final Logger log = LoggerFactory.getLogger(RenfieldScheduler.class);

    private final WatchlistRepository watchlist;
    private final AgoraMarketData marketData;
    private final AgoraCompanyData companyData;
    private final DaywalkerAlertRepository alerts;
    private final VerdictRepository verdicts;
    private final HeldPositionService heldPositions;
    private final PortfolioWeights portfolioWeights;
    private final SectorResolver sectors;
    private final VistierieClient vistierie;
    private final String publicUrl;
    private final String webhookToken;
    private final String connection;
    private final String owner;
    private final int maxSymbols;
    private final NewsEventTagger tagger = new NewsEventTagger();

    public RenfieldScheduler(WatchlistRepository watchlist, AgoraMarketData marketData,
            AgoraCompanyData companyData, DaywalkerAlertRepository alerts,
            VerdictRepository verdicts, HeldPositionService heldPositions,
            PortfolioWeights portfolioWeights, SectorResolver sectors,
            VistierieClient vistierie,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.renfield.webhook-token:dev-token-change-me}") String webhookToken,
            @Value("${dracul.position.connection:depot-1}") String connection,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.renfield.max-symbols:30}") int maxSymbols) {
        this.watchlist = watchlist;
        this.marketData = marketData;
        this.companyData = companyData;
        this.alerts = alerts;
        this.verdicts = verdicts;
        this.heldPositions = heldPositions;
        this.portfolioWeights = portfolioWeights;
        this.sectors = sectors;
        this.vistierie = vistierie;
        this.publicUrl = publicUrl;
        this.webhookToken = webhookToken;
        this.connection = connection;
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.maxSymbols = maxSymbols;
    }

    // zone is mandatory here — codebase precedent is split and the spec pins UTC.
    @Scheduled(cron = "${dracul.renfield.cron:0 0 12 * * MON-FRI}", zone = "UTC")
    public void run() {
        try {
            var items = watchlist.findAllByUser(owner);
            if (items.isEmpty()) {
                log.info("renfield: watchlist empty — skipping today's review (no run, no message)");
                return;
            }
            List<WatchlistItem> selected = items;
            if (items.size() > maxSymbols) {
                selected = items.stream()
                        .sorted(Comparator.comparingInt(RenfieldScheduler::priorityRank)
                                .thenComparing(WatchlistItem::addedAt, Comparator.reverseOrder()))
                        .limit(maxSymbols)
                        .toList();
                log.info("renfield: capped watchlist review to {} of {} symbols (dropped {})",
                        maxSymbols, items.size(), items.size() - maxSymbols);
            }
            var input = assembleInput(selected, Instant.now());
            vistierie.triggerRun("renfield", input,
                    publicUrl + "/api/renfield/complete", webhookToken);
            log.info("renfield review triggered for {} watchlist symbols", selected.size());
        } catch (RuntimeException e) {
            log.warn("renfield trigger failed: {}", e.getMessage());
        }
    }

    Map<String, Object> assembleInput(List<WatchlistItem> items, Instant now) {
        List<HeldPosition> open = heldPositions.openPositions(connection);
        Map<String, BigDecimal> weights = portfolioWeights.weightsBySymbol(open);
        Map<String, HeldPosition> heldBySymbol = new LinkedHashMap<>();
        for (HeldPosition p : PortfolioWeights.collapseBySymbol(open)) {
            heldBySymbol.putIfAbsent(p.symbol(), p);
        }
        Map<String, Quote> quotes = marketData.quotes(
                items.stream().map(WatchlistItem::ticker).toList());
        Instant since = now.minus(24, ChronoUnit.HOURS);
        LocalDate to = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate from = to.minusDays(1);

        var symbols = new ArrayList<Map<String, Object>>();
        for (WatchlistItem item : items) {
            var m = new LinkedHashMap<String, Object>();
            m.put("symbol", item.ticker());
            m.put("company_name", item.companyName());
            Quote q = quotes.get(item.ticker());
            m.put("current_price", q != null ? q.price() : item.currentPrice());
            m.put("day_change_percent", q != null ? q.dayChangePercent() : item.dayChangePercent());
            // T2.2 (D5): the bare `held` boolean is REPLACED by a real position block; absent
            // block = not held. gain_loss_pct uses the C1 snapshot formula (|mv|/|qty| vs
            // avgPrice) — renfield has no trigger close.
            HeldPosition p = heldBySymbol.get(item.ticker());
            if (p != null) {
                String direction = PositionMath.direction(p.quantity());
                var pos = new LinkedHashMap<String, Object>();
                pos.put("direction", direction);
                pos.put("entry", p.avgPrice());
                pos.put("gain_loss_pct", PositionMath.gainLossPct(direction, p.avgPrice(),
                        PositionMath.perUnitPrice(p.marketValue(), p.quantity())));
                pos.put("weight_pct", weights.get(item.ticker()));
                pos.put("active_stop", p.activeStop() != null ? p.activeStop() : p.initialStop());
                pos.put("sector", sectors.sector(item.ticker()));
                m.put("position", pos);
            } else {
                String sector = sectors.sector(item.ticker());
                if (sector != null) m.put("sector", sector);
            }
            m.put("news", newsFor(item.ticker(), from, to));
            m.put("alerts", alertsFor(item.ticker(), since));
            if (item.verdictId() != null) {
                verdicts.findLatestBySymbol(item.ticker()).ifPresent(v -> {
                    var vm = new LinkedHashMap<String, Object>();
                    vm.put("horizon", v.horizon());
                    vm.put("summary", v.summary());
                    vm.put("signals", v.signals());
                    vm.put("risks", v.risks());
                    m.put("verdict", vm);
                });
            }
            symbols.add(m);
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("as_of", now.toString());
        input.put("symbols", symbols);
        return input;
    }

    private List<Map<String, Object>> newsFor(String symbol, LocalDate from, LocalDate to) {
        var out = new ArrayList<Map<String, Object>>();
        for (NewsHeadline h : companyData.news(symbol, from, to)) {
            var n = new LinkedHashMap<String, Object>();
            n.put("headline", h.headline());
            n.put("source", h.source());
            n.put("datetime", h.datetime() == null ? null : h.datetime().toString());
            n.put("credibility", h.credibility());
            Set<NewsEventType> tags = tagger.tag(h);
            if (!tags.isEmpty()) {
                n.put("event_tags", tags.stream().map(NewsEventType::wireValue)
                        .collect(Collectors.joining(",")));
            }
            out.add(n);
        }
        return out;
    }

    /**
     * Priority stage for the cap: lower rank = reviewed first when the watchlist exceeds
     * {@code dracul.renfield.max-symbols}. First matching stage wins; verdict is
     * {@code verdictId != null}, NOT {@code source == "verdict"} -- the two diverge once a
     * verdict is merged onto a manual watchlist row.
     */
    private static int priorityRank(WatchlistItem i) {
        if ("HELD".equals(i.tag())) return 0;
        if (i.verdictId() != null) return 1;
        String s = i.source();
        if (s != null && s.startsWith("agent:")) return 2;
        if ("manual".equals(s)) return 3;
        if ("seed".equals(s)) return 4;
        return 5; // total fallback: lowest priority
    }

    private List<Map<String, Object>> alertsFor(String symbol, Instant since) {
        var out = new ArrayList<Map<String, Object>>();
        for (var a : alerts.recentAlerts(symbol, since)) {
            var m = new LinkedHashMap<String, Object>();
            m.put("trigger_type", a.triggerType());
            m.put("severity", a.severity());
            m.put("thesis", a.thesis());
            m.put("created_at", a.createdAt().toString());
            out.add(m);
        }
        return out;
    }
}
