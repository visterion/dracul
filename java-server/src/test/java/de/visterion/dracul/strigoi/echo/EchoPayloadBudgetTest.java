package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regressionsanker für das Tool-Result-Limit der Claude-Max-Bridge (25 000 Tokens ≈ 95 kB).
 *  Am 2026-07-22 riss der Echo-Payload dieses Limit bei ~115 kB; die Bridge lagerte das
 *  Ergebnis in eine Datei aus, die der Agent nicht lesen kann, und Echo lieferte 7 Tage lang
 *  leeres Prey OHNE Fehler — kein Log, kein Alarm, nur ein leerer Ergebnisstrom. Dieser Test
 *  macht ein erneutes Überschreiten im Build sichtbar statt erst nach Tagen stiller Leere.
 *
 *  <p><b>Kalibrierung (2026-07-28, mit dem Projektinhaber abgestimmt):</b> die beobachtete
 *  Kandidatenzahl der letzten 45 Tage lag bei maximal 29 (typisch 27–29 in einer
 *  Earnings-Woche, 11–14 sonst). {@code WORST_CASE_CANDIDATES = 45} ist das 1,55-fache dieses
 *  beobachteten Maximums — ein Sicherheitsaufschlag, keine Kapazitätsgrenze. Der reale
 *  Pro-Kandidat-Preis aus einem tatsächlichen Prod-Payload liegt bei ~875 B Metriken + 5
 *  Index-Items × ~165 B ≈ 1,7 kB/Kandidat; damit liegt die strukturelle Decke bei ~55
 *  Kandidaten gegen das ~95-kB-Bridge-Limit. {@code BUDGET_BYTES = 80_000} liegt komfortabel
 *  unter dieser 95-kB-Decke und deutlich über einem realistischen 45-Kandidaten-Payload — wer
 *  hier reißt, hat entweder ein Feld angebaut oder den Cap erhöht: beides ist eine bewusste
 *  Entscheidung, kein Versehen.
 *
 *  <p><b>Bekannte, akzeptierte Lücke:</b> ein Tag mit mehr als ~55 Kandidaten ist von diesem
 *  Test NICHT abgedeckt. Das ist eine dokumentierte, bewusst akzeptierte Lücke — kein Versehen.
 *  Sollte die reale Kandidatenzahl je in diese Nähe wachsen, muss dieser Test neu kalibriert
 *  werden (Kandidatenzahl serverseitig deckeln, recent-news-cap senken, oder ein Feld aus dem
 *  Index in {@code fetch_candidate_news} verschieben).
 *
 *  <p>Alle Werte sind SYNTHETISCH und an der Ø realer Prod-Werte kalibriert (Headline ~69
 *  Zeichen, {@code example.com} als offensichtlich synthetische Quelle nach RFC 2606). Nichts
 *  stammt aus Produktionsdaten. */
class EchoPayloadBudgetTest {

    private static final int WORST_CASE_CANDIDATES = 45;
    private static final int INDEX_ITEMS_PER_CANDIDATE = 5;
    private static final int BUDGET_BYTES = 80_000;

    private static EchoNewsIndexItem syntheticItem(int i) {
        return new EchoNewsIndexItem(
                "SYNTHETIC Holdings reports quarterly results, beats consensus, item " + i,
                "example.com", 0.62,
                Instant.parse("2026-01-05T14:30:00Z").plusSeconds(i * 3600L));
    }

    private static EnrichedPeadCandidate syntheticCandidate(int i) {
        List<EchoNewsIndexItem> index = new ArrayList<>();
        for (int n = 0; n < INDEX_ITEMS_PER_CANDIDATE; n++) index.add(syntheticItem(n));
        return new EnrichedPeadCandidate(
                "SYNTH" + i, "Synthetic Holdings Incorporated " + i,
                LocalDate.parse("2026-01-05"), 3,
                new BigDecimal("1.650000"), new BigDecimal("1.500000"), new BigDecimal("10.000000"),
                1.2345678901234, 9, false, true,
                new BigDecimal("2.345600"), true, 4, new BigDecimal("190.000000"),
                new BigDecimal("0.031000"), new BigDecimal("0.045000"), true,
                new BigDecimal("2.100000"), new BigDecimal("0.150000"), new BigDecimal("120000000.00"),
                2_500_000.0, 1.1234, "Professional Services", true,
                new BigDecimal("0.040000"), true, 5, "up", true,
                LocalDate.parse("2026-04-20"), 86, 12, true,
                index, 14);
    }

    @Test
    void worstCasePayloadStaysWellUnderTheBridgeToolResultLimit() {
        List<EnrichedPeadCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < WORST_CASE_CANDIDATES; i++) candidates.add(syntheticCandidate(i));

        String json = JsonMapper.builder().build().writeValueAsString(candidates);

        assertThat(json.length())
                .as("""
                    Echo-Payload-Budget gerissen (%d Kandidaten × %d Index-Items = %d Bytes).
                    Oberhalb von ~95 kB lagert die Claude-Max-Bridge das Tool-Ergebnis in eine
                    Datei aus, die der Agent nicht lesen kann — Echo liefert dann still leeres
                    Prey. Entweder ein Feld zurücknehmen, den recent-news-cap senken, oder das
                    Feld in das Detail-Tool fetch_candidate_news verschieben.""",
                    WORST_CASE_CANDIDATES, INDEX_ITEMS_PER_CANDIDATE, json.length())
                .isLessThan(BUDGET_BYTES);
    }
}
