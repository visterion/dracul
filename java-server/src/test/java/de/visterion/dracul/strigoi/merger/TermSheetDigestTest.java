package de.visterion.dracul.strigoi.merger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest exists because the raw summary term sheet is 95 % of a payload the model never got
 * to read (see {@link MergerPayloadBudgetTest}), and because its OPENING is the least useful part
 * of it. This is the verbatim head of a real production term sheet (Arcosa / CRH, run
 * {@code 74754073…}, 2026-08-04):
 *
 * <pre>
 *   Summary Term Sheet includes a page reference directing you to a more complete description
 *   of that topic. See "Where You Can Find More Information" beginning on page 93 ...
 *   The Parties to the Merger (page 19)
 *   Arcosa, Inc., headquartered in Dallas, Texas, is a provider of infrastructure-related ...
 * </pre>
 *
 * Page references, incorporation-by-reference boilerplate and where the registered office is.
 * A head-of-document excerpt would have spent the whole budget on that, which is why the digest
 * SELECTS sections instead of truncating.
 */
class TermSheetDigestTest {

    @Test void keepsTheRiskSectionsAndDropsTheBoilerplateHead() {
        String sheet = """
                Summary Term Sheet includes a page reference directing you to a more complete
                description of that topic. See "Where You Can Find More Information" beginning on
                page 93 for additional information regarding the documents incorporated by reference.
                The Parties to the Merger (page 19)
                Arcosa, Inc., headquartered in Dallas, Texas, is a provider of infrastructure-related
                products and solutions with leading positions in construction materials.
                Conditions to the Merger (page 71)
                Completion of the merger is subject to the expiration of the HSR waiting period and
                receipt of the required regulatory approvals in Ireland and Canada.
                Termination Fees (page 84)
                The Company must pay Parent a termination fee of $115 million if the merger
                agreement is terminated in specified circumstances.
                """;

        String digest = TermSheetDigest.of(sheet, 700);

        assertThat(digest).contains("Conditions to the Merger");
        assertThat(digest).contains("HSR waiting period");
        assertThat(digest).contains("Termination Fees");
        assertThat(digest).contains("$115 million");
        assertThat(digest).doesNotContain("Where You Can Find More Information");
        assertThat(digest).doesNotContain("headquartered in Dallas");
    }

    @Test void neverExceedsTheBudget() {
        String sheet = ("Conditions to the Merger\n" + "c".repeat(5_000) + "\n"
                + "Regulatory Approvals\n" + "r".repeat(5_000) + "\n"
                + "Termination Fee\n" + "t".repeat(5_000) + "\n"
                + "No Solicitation\n" + "s".repeat(5_000) + "\n");

        assertThat(TermSheetDigest.of(sheet, 700)).hasSizeLessThanOrEqualTo(700);
        assertThat(TermSheetDigest.of(sheet, 120)).hasSizeLessThanOrEqualTo(120);
    }

    @Test void fallsBackToTheHeadWhenNoSectionCueMatches() {
        // A filing whose summary uses no recognised heading must still tell the model SOMETHING —
        // an empty digest would be a worse outcome than boilerplate.
        String sheet = "Ceci n'est pas un term sheet. " + "z".repeat(3_000);

        String digest = TermSheetDigest.of(sheet, 300);

        assertThat(digest).hasSizeLessThanOrEqualTo(300);
        assertThat(digest).startsWith("Ceci n'est pas un term sheet.");
    }

    @Test void handlesAbsentAndBlankInput() {
        assertThat(TermSheetDigest.of(null, 700)).isEmpty();
        assertThat(TermSheetDigest.of("   ", 700)).isEmpty();
        assertThat(TermSheetDigest.of("anything", 0)).isEmpty();
    }

    @Test void prefersTheHigherValueSectionWhenTheBudgetOnlyFitsOne() {
        String sheet = """
                Financing (page 60)
                Parent has obtained fully committed debt financing from a syndicate of lenders.
                Conditions to the Merger (page 71)
                Completion is subject to the expiration of the HSR waiting period.
                """;

        // Budget for roughly one section: the closing conditions must win over financing even
        // though financing appears first in the document.
        String digest = TermSheetDigest.of(sheet, 130);

        assertThat(digest).contains("Conditions to the Merger");
        assertThat(digest).doesNotContain("syndicate of lenders");
    }
}
