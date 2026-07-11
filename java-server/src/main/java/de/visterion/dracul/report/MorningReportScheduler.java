package de.visterion.dracul.report;

import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/** Daily forcing-function: build each owner's morning report and push it as a
 *  Telegram digest. Gated off by default; never throws out of the scheduled
 *  method (a failure must not kill the scheduler thread). */
@Component
@ConditionalOnProperty(value = "dracul.report.morning.enabled", havingValue = "true")
public class MorningReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MorningReportScheduler.class);

    private final WatchlistRepository watchlist;
    private final MorningReportService service;
    private final TelegramNotifier telegram;

    public MorningReportScheduler(WatchlistRepository watchlist,
            MorningReportService service, TelegramNotifier telegram) {
        this.watchlist = watchlist;
        this.service = service;
        this.telegram = telegram;
    }

    @Scheduled(cron = "${dracul.report.morning.cron:0 0 7 * * 1-5}", zone = "Europe/Berlin")
    public void run() {
        try {
            Set<String> owners = new LinkedHashSet<>();
            for (WatchlistItem item : watchlist.findAll()) {
                if ("HELD".equals(item.tag())
                        && item.entryPrice() != null && item.shareCount() != null) {
                    owners.add(item.owner());
                }
            }
            if (owners.isEmpty()) return;  // no held positions → no push
            for (String owner : owners) {
                MorningReport report = service.build(owner);
                // Stay silent on a nothing-to-do day: only push when there is at
                // least one actionable position (SELL or TRIM).
                if (report.sellCount() + report.trimCount() == 0) continue;
                telegram.notifyDigest(render(owner, report));
            }
        } catch (RuntimeException e) {
            log.warn("morning report failed", e);
        }
    }

    /** Plain text (no parse_mode) — owner-prefixed, German, one line per position. */
    String render(String owner, MorningReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Morgen-Report [").append(owner).append("] — ")
          .append(r.sellCount()).append(" SELL / ")
          .append(r.trimCount()).append(" TRIM / ")
          .append(r.holdCount()).append(" HOLD\n");
        for (MorningReportLine l : r.positions()) {
            // Actionable-only body: list SELL/TRIM lines, omit HOLD.
            if (!"SELL".equals(l.action()) && !"TRIM".equals(l.action())) continue;
            String marker = switch (l.action()) {
                case "SELL" -> "🔴"; case "TRIM" -> "🟡"; default -> "⚪";
            };
            sb.append(marker).append(' ').append(l.action()).append(' ')
              .append(l.symbol());
            if (l.ticket().shares() > 0) sb.append(' ')
              .append(fmtShares(l.ticket().shares())).append(" Stk");
            sb.append(" — Stop ").append(fmt(l.activeStop()))
              .append(" / Ziel ").append(l.targetReached() ? "✓" : fmt(l.nextTarget2r()))
              .append(" / Kurs ").append(fmt(l.currentClose()));
            if (l.distanceToStopPct() != null) {
                sb.append(String.format(" (%+.1f%% z. Stop)", l.distanceToStopPct()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String fmt(BigDecimal v) { return v == null ? "—" : v.toPlainString(); }

    /** "10" for whole counts, "0.73" for fractional ones — never a truncating long cast. */
    private static String fmtShares(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
