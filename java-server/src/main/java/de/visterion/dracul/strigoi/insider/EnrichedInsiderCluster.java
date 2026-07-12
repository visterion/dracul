package de.visterion.dracul.strigoi.insider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A screened insider-buying cluster annotated with deterministic context so the LLM can
 *  judge the Lakonishok &amp; Lee setup (the effect concentrates in small, neglected names)
 *  against real numbers instead of guessing. All enrichment is fail-soft: each group of
 *  fields carries its own availability flag; a {@code false} flag means the sibling values
 *  are null (data source down or no data) — never a judgement about the cluster itself.
 *  {@code marketCap} comes from {@link de.visterion.dracul.strigoi.echo.EquityMetricsExtractor}
 *  (provider units, USD millions); {@code adv} = average daily dollar volume (close x volume)
 *  over the last 20 trading days. {@code metricsAvailable} is false only when ALL of
 *  {@code marketCap}/{@code adv} are null — the two come from different lookups, so a true
 *  flag does NOT guarantee both fields (e.g. OHLC delivered {@code adv} while the metrics
 *  blob was down, leaving {@code marketCap} null); a null field always means unknown.
 *  {@code analystCoverage} = analyst count in the latest
 *  recommendation-trend period; {@code ytdReturn} = (last close − first close of the calendar
 *  year) / first close, a decimal fraction; {@code nextEarningsDate}/{@code daysToEarnings}
 *  are informational only (no gate).
 *
 *  <p>Routine/opportunistic classification (Cohen, Malloy &amp; Pomorski 2012), aggregated over
 *  the cluster's filers from Agora owner history — the primary signal, since documented insider
 *  alpha sits in opportunistic (pattern-deviating) buyers, not routine calendar buyers:
 *  {@code opportunisticShare} = opportunistic ÷ (routine + opportunistic) as a decimal fraction,
 *  computed over the CLASSIFIABLE filers only and null when none could be classified;
 *  {@code classifiedFilers} = routine + opportunistic; {@code unknownFilers} = filers with too
 *  little/incomplete history or no owner match (never counted as opportunistic).
 *  {@code classificationAvailable} is false when the owner-history source was down/skipped for
 *  this cluster — then every filer is UNKNOWN and {@code opportunisticShare} is null. */
public record EnrichedInsiderCluster(
        String ticker,
        String companyName,
        List<InsiderFiler> filers,
        LocalDate windowStart,
        LocalDate windowEnd,
        BigDecimal totalDollarValue,
        BigDecimal totalShares,
        int concurrentInsiderSells,
        BigDecimal netInsiderDollar,
        Double marketCap,
        BigDecimal adv,
        boolean metricsAvailable,
        Integer analystCoverage,
        boolean coverageAvailable,
        BigDecimal ytdReturn,
        boolean ytdReturnAvailable,
        LocalDate nextEarningsDate,
        Integer daysToEarnings,
        boolean earningsDateAvailable,
        BigDecimal opportunisticShare,
        int classifiedFilers,
        int unknownFilers,
        boolean classificationAvailable
) {}
