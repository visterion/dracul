package de.visterion.dracul.strigoi.tech;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The code verdict on one strigoi-tech candidate (spec 2026-10-03 §4.2). Code rejects only what
 * is clearly out of scope — there is deliberately NO sector/industry filter (provider industry
 * labels file large tech platforms under "Media" or "Retail"; what counts as tech is the LLM's
 * call). Partial data is {@code data_unavailable:<field>}, never a silent pass.
 *
 * <p>Market cap is enforced only when the listing is CONFIRMED ({@code profile.ticker} equals the
 * symbol — the discriminator LazarusListingResolver uses): for a foreign primary listing the
 * profile reports the home market's cap in its own currency, so the check is reported as the
 * non-blocking note {@code market_cap_unverified} and tradability is left to the LIQUIDITY veto.
 */
public final class TechEligibility {

    public static final String EQUITY = "EQUITY";

    public record Inputs(String symbol, String instrumentType, String quoteCurrency,
            String profileTicker, BigDecimal marketCapMillions, boolean held, boolean pending,
            boolean recentlyExited) {
    }

    /** {@code reasons} block, {@code notes} inform. */
    public record Verdict(boolean eligible, List<String> reasons, List<String> notes) {
    }

    private TechEligibility() {
    }

    public static Verdict evaluate(Inputs in, BigDecimal minMarketCapUsdMillions,
            String instrumentCurrency) {
        List<String> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (blank(in.instrumentType())) {
            reasons.add("data_unavailable:instrument_type");
        } else if (!EQUITY.equalsIgnoreCase(in.instrumentType().trim())) {
            reasons.add("not_equity:" + in.instrumentType().trim().toUpperCase(Locale.ROOT));
        }
        if (blank(in.quoteCurrency())) {
            reasons.add("data_unavailable:quote_currency");
        } else if (!in.quoteCurrency().trim().equalsIgnoreCase(instrumentCurrency)) {
            reasons.add("quote_currency:" + in.quoteCurrency().trim().toUpperCase(Locale.ROOT));
        }
        if (in.held()) reasons.add("already_held");
        if (in.pending()) reasons.add("already_pending");
        if (in.recentlyExited()) reasons.add("recently_exited");

        if (blank(in.profileTicker())) {
            reasons.add("data_unavailable:profile");
        } else if (in.profileTicker().trim().equalsIgnoreCase(in.symbol().trim())) {
            if (in.marketCapMillions() == null) {
                reasons.add("data_unavailable:market_cap");
            } else if (in.marketCapMillions().compareTo(minMarketCapUsdMillions) < 0) {
                reasons.add("market_cap_below_min");
            }
        } else {
            notes.add("market_cap_unverified");
        }
        return new Verdict(reasons.isEmpty(), List.copyOf(reasons), List.copyOf(notes));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
