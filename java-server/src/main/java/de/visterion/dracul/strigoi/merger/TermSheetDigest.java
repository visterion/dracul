package de.visterion.dracul.strigoi.merger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reduces a filing's summary term sheet to the sections that price DEAL RISK, inside a hard
 * character budget.
 *
 * <p><b>Why this exists.</b> Agora caps {@code get_filing_text} at 24 000 characters per filing
 * and Dracul shipped that verbatim to the model — 25 candidates x ~13 kB average = a 329 818-char
 * tool result on the production run of 2026-08-04. The Claude Code CLI cuts an MCP tool result at
 * {@code MAX_MCP_OUTPUT_TOKENS * 4} = 100 000 characters, so the model received a candidate list
 * chopped mid-JSON and answered {@code {"prey": []}} on five consecutive runs, every one of them
 * {@code status=done}. The hunter was blind and looked healthy. See
 * {@code MergerPayloadBudgetTest} for the located limit and the payload arithmetic.
 *
 * <p><b>Why a DIGEST and not a truncation.</b> The head of a summary term sheet is the least
 * useful part of it. Verbatim, from the Arcosa/CRH DEFM14A in that same run: page references,
 * {@code "See “Where You Can Find More Information” beginning on page 93"},
 * {@code "The Parties to the Merger (page 19)"}, and three paragraphs on where each entity is
 * incorporated. A head excerpt spends the entire budget before reaching a single fact that moves
 * a closing probability.
 *
 * <p><b>Why so little is actually needed.</b> Everything QUANTITATIVE the prompt used to ask the
 * model to mine out of this prose is already extracted server-side by {@link DealTermsParser} and
 * rides the payload as its own field: offer price, consideration type, exchange ratio, break fee,
 * agreement date, expected close date, outside date — plus the spread, annualized spread,
 * unaffected price and break downside computed from them. What parsing cannot deliver is the
 * qualitative closing risk: which approvals are outstanding, what the conditions are, whether
 * financing is committed, whether a go-shop is running. That is what the cues below select, in
 * descending order of how much they move a merger-arb judgement.
 *
 * <p>This is a strict improvement on what it replaces: the model went from 24 000 characters it
 * never saw to {@code budgetChars} it does.
 */
public final class TermSheetDigest {

    private TermSheetDigest() {}

    /**
     * Section cues in priority order — the budget is spent from the top, so when only one section
     * fits it is the one that decides most.
     *
     * <p>Ordering rationale: closing CONDITIONS are the deal's own list of what can still stop it;
     * REGULATORY approval is the single most common reason a spread stays wide; TERMINATION fees
     * price both sides' commitment; SOLICITATION says whether a topping bid is still possible;
     * FINANCING is the buyer-side failure mode; the shareholder VOTE is the most predictable of
     * the gates and therefore last.
     */
    private static final List<String[]> CUES = List.of(
            new String[]{"conditions to the", "conditions of the", "conditions to completion",
                    "conditions to the closing", "closing conditions"},
            new String[]{"regulatory approval", "regulatory matters", "antitrust",
                    "hsr act", "hart-scott-rodino", "cfius", "required approvals"},
            new String[]{"termination fee", "termination fees", "termination of the merger agreement",
                    "expenses and termination"},
            new String[]{"no solicitation", "solicitation of other offers", "go-shop",
                    "competing proposal", "acquisition proposals"},
            new String[]{"financing", "financing of the merger", "debt financing"},
            new String[]{"required vote", "vote required", "stockholder approval",
                    "shareholder approval", "record date and voting"});

    /**
     * Longest slice taken from any one section. Small on purpose: three short slices beat one long
     * one, because the sections are independent risks and the model needs to know a risk EXISTS
     * far more than it needs the sub-clause detail. A section is cut on a word boundary.
     */
    private static final int MAX_SECTION_CHARS = 240;

    /**
     * @param termSheet   the raw filing text; may be null or blank
     * @param budgetChars hard ceiling on the returned string; the payload arithmetic in
     *                    {@code MergerPayloadBudgetTest} depends on this never being exceeded
     * @return the digest, never null, never longer than {@code budgetChars}
     */
    public static String of(String termSheet, int budgetChars) {
        if (termSheet == null || termSheet.isBlank() || budgetChars <= 0) return "";

        String lower = termSheet.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        int remaining = budgetChars;

        for (String[] cueGroup : CUES) {
            if (remaining <= 0) break;
            int at = firstMatch(lower, cueGroup);
            if (at < 0) continue;
            // A section slice starts AT the heading, so the model always sees what the following
            // sentences are about — an unlabelled fragment is worse than no fragment.
            int take = Math.min(Math.min(MAX_SECTION_CHARS, remaining), termSheet.length() - at);
            String slice = trimToWordBoundary(termSheet.substring(at, at + take));
            if (slice.isBlank()) continue;
            // Separator is only affordable when it, too, fits.
            int cost = slice.length() + (parts.isEmpty() ? 0 : 1);
            if (cost > remaining) continue;
            parts.add(slice);
            remaining -= cost;
        }

        if (parts.isEmpty()) {
            // No recognised heading. A filing whose summary is written unconventionally must still
            // tell the model something — an empty digest is a worse answer than boilerplate.
            return trimToWordBoundary(termSheet.substring(0, Math.min(budgetChars, termSheet.length())));
        }
        return String.join("\n", parts);
    }

    private static int firstMatch(String lowerText, String[] cues) {
        int best = -1;
        for (String cue : cues) {
            int at = lowerText.indexOf(cue);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** Drops a trailing partial word so a slice never ends mid-token; keeps the slice unchanged
     *  when there is no interior whitespace to cut at. */
    private static String trimToWordBoundary(String s) {
        String t = s.strip();
        if (t.length() < 40) return t;
        int lastSpace = t.lastIndexOf(' ');
        return lastSpace > t.length() - 25 ? t.substring(0, lastSpace).strip() : t;
    }
}
