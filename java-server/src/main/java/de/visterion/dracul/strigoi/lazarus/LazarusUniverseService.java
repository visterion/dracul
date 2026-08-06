package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.hunting.agora.PriceRange;
import de.visterion.dracul.hunting.agora.RangeProbe;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Stage 1 of the quality-at-52-week-low hunt: narrows a market-wide index universe (~500
 * symbols) down to the handful that are anywhere near their 52-week low, using cheap
 * {@link AgoraPriceRange} 52-week-range probes (served by Agora's OHLC provider chain, Alpaca
 * first).
 *
 * <p><b>The universe is walked in CHUNKS, not symbol by symbol.</b> Until 2026-08-06 this was one
 * {@code get_indicators} call per index member, ~490 per run, and it was measured tearing through
 * Alpaca's per-minute quota: 49 of 645 Alpaca calls answered 429 on 2026-08-05, TwelveData (8
 * credits/minute) tipped over right behind it and Yahoo carried the remainder. One
 * {@code get_indicators_batch} call per chunk of {@code probe-chunk-size} (default 100) turns that
 * into ~5 calls. Nothing about the COUNTING changed with it — a chunk that answers for fewer
 * symbols than it was handed has those symbols counted as degradations, one by one, exactly as if
 * each had failed on its own.
 *
 * <p><b>Why a pre-filter is not optional.</b> The authoritative screen
 * ({@link LazarusScreener}) reads its 52-week low, solvency and valuation metrics out of
 * {@code get_fundamentals}, which routes US symbols to Finnhub — throttled to 60 calls/minute
 * across all of Agora. A naive loop over ~500 index members would spend more than eight minutes
 * inside the throttle, collect 429s, and silently drop most of the universe: the exact failure
 * this hunter is being fixed for, only with a bigger number. So the expensive call is spent only
 * on the survivors of this stage.
 *
 * <p><b>Four bounded ways to lose symbols, all counted — but only three of them are degradations.</b>
 * None of them may look like a quiet market, and the one that is not a degradation must not look
 * like one either:
 * <ul>
 *   <li><b>{@code notEligible}</b> — the instrument has no 52-week window yet (freshly listed).
 *       NOT a degradation: nothing failed, and no retry, no failover and no bigger budget will ever
 *       produce a range until the symbol ages. Reported in the log line only, never as
 *       {@code partial}. This split was measured: on 2026-08-05 a run that screened all 490 symbols
 *       it was handed reported {@code partial=true} solely because of FDXF, HONA and Q — three
 *       S&amp;P 500 members younger than 52 weeks (Q, Qnity Electronics, first traded 2025-10-27) —
 *       and the daily analysis raised its "incomplete answer" alarm every single night for it.</li>
 *   <li><b>{@code probeFailed}</b> — a real degradation: Agora answered with something unusable
 *       (no close, non-positive low, the requested spec missing), the chunk call failed outright,
 *       or the chunk came back WITHOUT an entry for that symbol. That last case is the one the
 *       batch path adds, and it is the one that has to be loud: a batched answer that silently
 *       covers 60 of 100 symbols reads downstream exactly like a quiet market.
 *       Reported as {@code partial}.</li>
 *   <li><b>{@code unscreened}</b> — the wall-clock budget ran out before the universe was walked.
 *       Reported as {@code truncated}. The budget is checked after each CHUNK (the call is
 *       indivisible, so it can overshoot by at most one chunk's duration — one Agora request,
 *       bounded by {@code dracul.agora.timeout-ms}). The scan starts at a caller-supplied rotating
 *       offset and wraps, so a permanently tight budget still covers the whole index over
 *       successive runs instead of re-screening the same head of the alphabet forever.</li>
 *   <li><b>{@code sourceDown}</b> — a RUN of consecutive {@code probeFailed} events, counted in
 *       SYMBOLS and not in chunks: a wholly failed chunk contributes as many consecutive failures
 *       as it had symbols, so the threshold means the same number it always did whatever the chunk
 *       size is. One failure
 *       still proves nothing (a single bad body can be the symbol's fault), a run of them does, and
 *       stopping there saves hundreds of dead remote calls. An ineligible symbol deliberately does
 *       NOT touch that run counter: index constituents are walked in list order, so a handful of
 *       adjacent young listings would otherwise reach {@code maxConsecutiveFailures} (10 by
 *       default, {@code dracul.strigoi.lazarus.max-consecutive-failures}), abort the pass
 *       mid-universe and declare a perfectly healthy Agora down. It does not RESET the counter
 *       either — a young symbol interleaved into a genuine outage must not paper over it.</li>
 * </ul>
 *
 * <p>Telling the first two apart only became possible at commit e5db10be, which made the MCP
 * {@code isError} envelope the sole outage discriminator in {@code AgoraClient.parseToolText}.
 * Until then "no history for this symbol" and "Agora is unreachable" arrived through the same
 * channel and had to be counted together; now the young symbol comes back as a normal body
 * ({@code available:false}) and only an outage throws.
 */
@Component
public class LazarusUniverseService {

    private static final Logger log = LoggerFactory.getLogger(LazarusUniverseService.class);

    /**
     * One symbol on its way to the expensive stage, with the price it is judged on and how far
     * that price sits above its 52-week low. {@code pctAboveLow} ranks the shortlist when the
     * fundamentals budget cannot cover it all — the only meaningful priority available BEFORE any
     * fundamentals have been fetched. Watchlist entries, which bypass the pre-filter entirely,
     * are constructed with {@link Double#NEGATIVE_INFINITY} so they always rank first.
     */
    public record PreScreened(String symbol, String companyName, double currentPrice, double pctAboveLow) {

        /** A watchlist name: no pre-filter measurement, unconditionally first in the ranking. */
        public static PreScreened unconditional(String symbol, String companyName, double currentPrice) {
            return new PreScreened(symbol, companyName, currentPrice, Double.NEGATIVE_INFINITY);
        }
    }

    /**
     * Outcome of one pre-filter pass.
     *
     * @param shortlist   symbols within the margin of their 52-week low
     * @param considered  size of the universe handed in
     * @param screened    symbols actually probed
     * @param probeFailed probed symbols lost to a DEGRADATION: Agora answered unusably or the call
     *                    failed. This is the number that drives {@code partial}.
     * @param notEligible probed symbols that simply have no 52-week window yet (freshly listed).
     *                    Counted for the log line, never reported as a degradation.
     * @param unscreened  {@code considered - screened}; &gt; 0 means the pass was cut short
     * @param sourceDown  the pass stopped on a run of consecutive failures
     */
    public record Scan(List<PreScreened> shortlist, int considered, int screened,
                       int probeFailed, int notEligible, int unscreened, boolean sourceDown) {}

    /**
     * Symbols per {@code get_indicators_batch} call when nothing else is configured. 100 is
     * measured, not guessed: 100 symbols in one Alpaca multi-symbol bars URL answered HTTP 200 in
     * 1.15 s (2026-08-06), and it sits an order of magnitude below Agora's own 600-symbol ceiling,
     * which rejects rather than truncates.
     */
    static final int DEFAULT_CHUNK_SIZE = 100;

    private final AgoraPriceRange priceRange;
    private final LongSupplier clock;
    private final int chunkSize;

    /** @Autowired is REQUIRED: the constructors below make this a multi-constructor bean, and
     *  Spring refuses to guess between them. */
    @Autowired
    public LazarusUniverseService(
            AgoraPriceRange priceRange,
            @Value("${dracul.strigoi.lazarus.probe-chunk-size:" + DEFAULT_CHUNK_SIZE + "}") int chunkSize) {
        this(priceRange, System::currentTimeMillis, chunkSize);
    }

    public LazarusUniverseService(AgoraPriceRange priceRange) {
        this(priceRange, System::currentTimeMillis, DEFAULT_CHUNK_SIZE);
    }

    /** Test seam: injected wall clock, so budget exhaustion is exercised without sleeping. */
    LazarusUniverseService(AgoraPriceRange priceRange, LongSupplier clock) {
        this(priceRange, clock, DEFAULT_CHUNK_SIZE);
    }

    /** Test seam: wall clock AND chunk size, so chunk-granular behaviour is exercised without a
     *  100-symbol fixture. Clamped into [1, Agora's cap] — a misconfigured 0 must not stall the
     *  pass, and a misconfigured 5000 must not produce a call Agora rejects outright. */
    LazarusUniverseService(AgoraPriceRange priceRange, LongSupplier clock, int chunkSize) {
        this.priceRange = priceRange;
        this.clock = clock;
        this.chunkSize = Math.clamp(chunkSize, 1, AgoraPriceRange.MAX_BATCH_SYMBOLS);
    }

    /**
     * @param universe                the symbols to consider (index constituents)
     * @param margin                  keep symbols at most this fraction above their 52-week low.
     *                                Deliberately WIDER than the authoritative screen threshold:
     *                                this stage reads a 252-bar low off the daily-OHLC provider
     *                                chain (Alpaca first) while the screen reads
     *                                the provider's own 52-week low, and a pre-filter that cut
     *                                tighter than the screen would drop real candidates over a
     *                                definitional difference.
     * @param budgetMs                wall-clock ceiling for the whole pass
     * @param maxConsecutiveFailures  failures in a row before the source is declared down
     * @param rotationOffset          index to start at; the pass wraps around the universe
     */
    public Scan preScreen(List<IndexConstituent> universe, double margin, long budgetMs,
                          int maxConsecutiveFailures, int rotationOffset) {
        int size = universe.size();
        if (size == 0) return new Scan(List.of(), 0, 0, 0, 0, 0, false);

        long deadline = clock.getAsLong() + budgetMs;
        int start = Math.floorMod(rotationOffset, size);
        List<PreScreened> shortlist = new ArrayList<>();
        int screened = 0;
        int probeFailed = 0;
        int notEligible = 0;
        int consecutiveFailures = 0;
        boolean sourceDown = false;

        chunks:
        for (int i = 0; i < size; i += chunkSize) {
            List<IndexConstituent> chunk = new ArrayList<>(Math.min(chunkSize, size - i));
            for (int j = i; j < Math.min(i + chunkSize, size); j++) {
                chunk.add(universe.get((start + j) % size));
            }

            // ONE call for the whole chunk. An outage tells us nothing about any symbol in it, so
            // every one of them is a degradation — the same verdict each would have earned from
            // its own failed call, which is what keeps the consecutive-failure threshold meaning
            // the same number of SYMBOLS it always did.
            Map<String, RangeProbe> answer;
            try {
                answer = priceRange.range52wBatch(chunk.stream().map(IndexConstituent::symbol).toList());
            } catch (AgoraUnavailableException e) {
                answer = Map.of();
                log.warn("lazarus pre-filter: 52w-range chunk of {} symbols starting at {} failed ({}) "
                        + "— counting all {} as degradations", chunk.size(), chunk.getFirst().symbol(),
                        e.getMessage(), chunk.size());
            }
            final Map<String, RangeProbe> probes = answer;

            long answered = chunk.stream().filter(c -> probes.containsKey(c.symbol())).count();
            if (answered < chunk.size() && !probes.isEmpty()) {
                // Never a shrug. A batch answer that covers fewer symbols than it was asked for is
                // the failure mode this project keeps meeting: silent partial coverage that reads
                // like a quiet market. Named here, counted below, surfaced as probeFailed.
                log.warn("lazarus pre-filter: 52w-range chunk answered for {} of {} symbols "
                        + "— the missing {} count as degradations",
                        answered, chunk.size(), chunk.size() - answered);
            }

            for (IndexConstituent c : chunk) {
                screened++;
                RangeProbe probe = probes.get(c.symbol());
                if (probe == null) {
                    probeFailed++;
                    consecutiveFailures++;
                    log.debug("lazarus pre-filter: no 52w-range entry for {} in the batch answer",
                            c.symbol());
                } else {
                    switch (probe.kind()) {
                        case OK -> {
                            consecutiveFailures = 0;
                            PriceRange r = probe.range();
                            if (r.pctAboveLow() <= margin) {
                                shortlist.add(new PreScreened(c.symbol(), c.companyName(),
                                        r.currentClose().doubleValue(), r.pctAboveLow()));
                            }
                        }
                        // A symbol too young for a 52-week window: counted, but neither a degradation
                        // nor evidence about the source, so the run counter is left exactly as it was
                        // — not incremented (young listings cluster in list order and would abort the
                        // pass) and not reset (that would hide a genuine outage running through them).
                        case NOT_ELIGIBLE -> notEligible++;
                        case UNUSABLE -> {
                            probeFailed++;
                            consecutiveFailures++;
                            log.debug("lazarus pre-filter: unusable 52w-range body for {}", c.symbol());
                        }
                    }
                }
                // Checked per SYMBOL, inside the chunk: the call is already paid for, but the pass
                // must still stop at the same failure count it did before chunking, or `screened`
                // would silently grow to the chunk boundary and claim symbols nobody looked at.
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    sourceDown = true;
                    log.warn("lazarus pre-filter: {} consecutive 52w-range failures — treating the source as "
                            + "down and leaving {} of {} universe symbols unscreened",
                            consecutiveFailures, size - screened, size);
                    break chunks;
                }
            }

            if (clock.getAsLong() >= deadline) {
                log.info("lazarus pre-filter: {} ms budget spent after {} of {} universe symbols",
                        budgetMs, screened, size);
                break;
            }
        }
        return new Scan(List.copyOf(shortlist), size, screened, probeFailed, notEligible,
                size - screened, sourceDown);
    }
}
