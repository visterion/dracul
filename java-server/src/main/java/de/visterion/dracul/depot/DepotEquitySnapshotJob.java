package de.visterion.dracul.depot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Writes the measured equity of every depot connection into {@code depot_equity_snapshot}.
 *
 * <p>Talks to {@link AgoraDepotClient} directly rather than through {@code DepotService}:
 * {@code DepotService.isLiveVisible} returns false when there is no user email, and a scheduler
 * thread has no CurrentUserHolder -- a live depot would be invisible to this job and its series
 * would stay empty without a single error. {@code PositionReconciler} avoids the same trap.
 *
 * <p>Runs on its own single-threaded scheduler (see
 * {@code de.visterion.dracul.config.SchedulingConfig}) so it cannot delay the jobs sharing the
 * default pool.
 *
 * <p><b>Never writes an invented number.</b> A missing equity, cash or currency skips the
 * connection with a WARN; a gap in the curve is honest, a zero row would render as a total loss.
 */
@Component
@ConditionalOnProperty(value = "dracul.depots.equity-snapshot.enabled",
        havingValue = "true", matchIfMissing = true)
public class DepotEquitySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(DepotEquitySnapshotJob.class);

    private final AgoraDepotClient depotClient;
    private final DepotEquitySnapshotRepository repo;
    private final Clock clock;

    @Autowired
    public DepotEquitySnapshotJob(AgoraDepotClient depotClient,
                                  DepotEquitySnapshotRepository repo) {
        this(depotClient, repo, Clock.systemUTC());
    }

    /** Package-private overload with an injectable {@link Clock} so tests can assert the as_of
     *  label without freezing wall-clock time — same shape as {@code EntryExpiryService:65}.
     *  There is no global Clock bean in this codebase on purpose. */
    DepotEquitySnapshotJob(AgoraDepotClient depotClient,
                           DepotEquitySnapshotRepository repo,
                           Clock clock) {
        this.depotClient = depotClient;
        this.repo = repo;
        this.clock = clock;
    }

    /** Per-run tally, returned so tests can assert it and the log line can print it. */
    public record CaptureResult(int written, int skipped) {
    }

    @Scheduled(cron = "${dracul.depots.equity-snapshot.daily-cron:0 45 21 * * 1-5}",
            zone = "UTC", scheduler = "equitySnapshotScheduler")
    public void captureDaily() {
        capture("DAILY", Instant.now(clock).truncatedTo(ChronoUnit.DAYS));
    }

    @Scheduled(cron = "${dracul.depots.equity-snapshot.intraday-cron:0 5,20,35,50 13-21 * * 1-5}",
            zone = "UTC", scheduler = "equitySnapshotScheduler")
    public void captureIntraday() {
        capture("INTRADAY", Instant.now(clock).truncatedTo(ChronoUnit.MINUTES));
    }

    CaptureResult capture(String granularity, Instant asOf) {
        List<DepotConnection> connections;
        try {
            connections = depotClient.listConnections();
        } catch (RuntimeException e) {
            // Its own line: with no connection to name, a per-connection WARN cannot fire, and a
            // clean return would be indistinguishable from a successful run in which nothing
            // changed.
            log.warn("equity snapshot [{}]: connection enumeration failed — 0 rows written ({})",
                    granularity, e.toString());
            return new CaptureResult(0, 0);
        }
        if (connections.isEmpty()) {
            log.warn("equity snapshot [{}]: connection enumeration empty — 0 rows written",
                    granularity);
            return new CaptureResult(0, 0);
        }

        int written = 0;
        int skipped = 0;
        for (DepotConnection c : connections) {
            if (captureOne(c.id(), granularity, asOf)) {
                written++;
            } else {
                skipped++;
            }
        }
        log.info("equity snapshot [{}]: {}/{} connections written, {} skipped",
                granularity, written, connections.size(), skipped);
        return new CaptureResult(written, skipped);
    }

    private boolean captureOne(String connection, String granularity, Instant asOf) {
        try {
            DepotAccount account = depotClient.account(connection);
            if (account == null) {
                log.warn("equity snapshot [{}]: {} returned no account — skipped",
                        granularity, connection);
                return false;
            }
            BigDecimal equity = account.equity();
            BigDecimal cash = account.cash();
            String currency = account.currency();
            if (equity == null || cash == null || currency == null) {
                log.warn("equity snapshot [{}]: {} incomplete account "
                                + "(equity={}, cash={}, currency={}) — skipped",
                        granularity, connection, equity, cash, currency);
                return false;
            }

            Optional<DepotEquitySnapshotRepository.SnapshotWrite> w =
                    repo.upsert(connection, asOf, granularity, equity, cash, currency);
            if (w.isEmpty()) {
                log.debug("equity snapshot [{}]: {} unchanged at {}",
                        granularity, connection, asOf);
            } else if (!w.get().inserted()) {
                log.info("equity snapshot corrected: connection={} granularity={} as_of={} id={}",
                        connection, granularity, asOf, w.get().id());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("equity snapshot [{}]: {} failed — skipped ({})",
                    granularity, connection, e.toString());
            return false;
        }
    }
}
