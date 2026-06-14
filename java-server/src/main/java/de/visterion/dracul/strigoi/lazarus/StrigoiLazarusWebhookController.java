package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.finnhub.FinnhubFundamentalsAdapter;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import de.visterion.dracul.webhook.HuntController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.lazarus.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-lazarus")
public class StrigoiLazarusWebhookController extends HuntController {

    private static final String USER = "default";

    private final WatchlistRepository watchlist;
    private final FinnhubFundamentalsAdapter fundamentals;
    private final LazarusScreener screener;
    private final double maxAboveLow;
    private final double maxDebtEquity;

    public StrigoiLazarusWebhookController(
            @Value("${dracul.strigoi.lazarus.webhook-token}") String token,
            WatchlistRepository watchlist,
            FinnhubFundamentalsAdapter fundamentals,
            LazarusScreener screener,
            PreyRepository preyRepo,
            @Value("${dracul.strigoi.lazarus.max-above-low:0.10}") double maxAboveLow,
            @Value("${dracul.strigoi.lazarus.max-debt-equity:3.0}") double maxDebtEquity) {
        super(token, preyRepo);
        this.watchlist = watchlist;
        this.fundamentals = fundamentals;
        this.screener = screener;
        this.maxAboveLow = maxAboveLow;
        this.maxDebtEquity = maxDebtEquity;
    }

    @Override protected String agentName() { return "strigoi-lazarus"; }
    @Override protected String defaultAnomalyType() { return "QUALITY_52W_LOW"; }
    @Override protected String defaultHorizon() { return "12m"; }
    @Override protected boolean skipBlankSymbol() { return true; }

    @Override
    protected List<?> hunt(Map<String, Object> body) {
        var items = watchlist.findAllByUser(USER);
        var raws = new ArrayList<LazarusRaw>();
        for (WatchlistItem item : items) {
            raws.add(new LazarusRaw(
                    item.ticker(), item.companyName(),
                    item.currentPrice(), fundamentals.basicFinancials(item.ticker())));
        }
        return screener.screen(raws, maxAboveLow, maxDebtEquity);
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }
}
