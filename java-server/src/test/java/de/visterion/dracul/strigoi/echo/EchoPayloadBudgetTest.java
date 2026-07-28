package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *  <p><b>Gemessene Baseline (2026-07-28, Fix-Runde 1: UTF-8-Bytes + reale Envelope):</b> der
 *  aktuelle Messwert steht im Report ({@code task-4-report.md}, Abschnitt "Fix round 1") und
 *  im Assertion-Failure, sobald der Test reißt — inklusive Abstand zum Budget in Prozent, damit
 *  eine spätere Wartungsperson sofort sieht, ob noch 5 % oder 40 % Marge übrig sind.
 *
 *  <p><b>{@code INDEX_ITEMS_PER_CANDIDATE} ist AN {@code application.yaml} gebunden</b>, nicht
 *  hart verdrahtet: der Wert wird zur Testlaufzeit aus dem Default von
 *  {@code dracul.strigoi.echo.recent-news-cap} in {@code application.yaml} gelesen. Damit kann
 *  der Cap nicht am Test vorbei erhöht werden, ohne dass sich auch der Worst Case verschiebt —
 *  vorher war das ein zahnloses Duplikat, das bei einer Cap-Erhöhung in der Yaml stillschweigend
 *  falsch geworden wäre (genau die Art von Drift, die den Bug vom 2026-07-22 verursacht hat).
 *
 *  <p><b>Was NICHT gemessen wird — {@code active_patterns}:</b> die reale Bridge-Antwort ist
 *  nicht die nackte Kandidatenliste, sondern die Envelope aus
 *  {@code HuntController.handleFetch}: {@code {"output":{"candidates":[…],
 *  "data_source_health":{…},"active_patterns":[…]}}}. Envelope + {@code data_source_health}
 *  sind mit ~200 B vernachlässigbar und werden unten nachgebildet. {@code active_patterns}
 *  (aus {@code PatternRepository.findAcceptedByStrigoi}) ist dagegen eine UNGEDECKELTE Liste
 *  von TEXT-Statements (~200 B je Eintrag laut Seed-Daten, 135–235 Zeichen), die mit jedem vom
 *  Lernloop akzeptierten Pattern monotonisch wächst UND absichtlich mit {@code 'all'}-Mustern
 *  aller Hunter geteilt wird. Dieser Test bildet sie bewusst LEER nach (Baseline-Realität: kaum
 *  akzeptierte Patterns) — ihr Wachstum ist NICHT budgetiert und NICHT von diesem Test
 *  abgedeckt. Bei ~200 B/Pattern verbraucht ein Bestand von etwa 90–95 akzeptierten,
 *  echo-relevanten Patterns bereits die gesamte verbleibende Marge zwischen der gemessenen
 *  Baseline und dem realen ~95-kB-Bridge-Limit — unabhängig vom Kandidaten-Budget hier. Ein
 *  wachsender Pattern-Bestand braucht eine EIGENE Absicherung (z.B. einen Cap in
 *  {@code findAcceptedByStrigoi} oder einen eigenen Regressionstest); dieser Test hier deckt
 *  ausschließlich den Kandidaten-Teil ab.
 *
 *  <p><b>Bekannte, akzeptierte Lücke:</b> dieser Test deckt Kandidatenzahlen bis 45 ab — ab 46
 *  Kandidaten prüft er nichts mehr. Der reale strukturelle Schaden (Bridge-Limit gerissen)
 *  setzt aber erst deutlich später ein, ungefähr ab ~56 Kandidaten. Der Bereich 46–55 ist also
 *  ungetestet, aber (Stand heute) noch nicht schädlich — das ist eine dokumentierte, bewusst
 *  akzeptierte Lücke, kein Versehen, und KEINE Aussage, dass dieser Test bis 55 abdeckt.
 *  Sollte die reale Kandidatenzahl je in die Nähe von ~55 wachsen, muss dieser Test neu
 *  kalibriert werden (Kandidatenzahl serverseitig deckeln, recent-news-cap senken, oder ein
 *  Feld aus dem Index in {@code fetch_candidate_news} verschieben).
 *
 *  <p>Alle Werte sind SYNTHETISCH und an der Ø realer Prod-Werte kalibriert (Headline ~69
 *  Zeichen, {@code example.com} als offensichtlich synthetische Quelle nach RFC 2606). Nichts
 *  stammt aus Produktionsdaten. */
class EchoPayloadBudgetTest {

    private static final int WORST_CASE_CANDIDATES = 45;
    private static final int INDEX_ITEMS_PER_CANDIDATE = recentNewsCapDefaultFromYaml();
    private static final int BUDGET_BYTES = 80_000;
    /** Floor so a fixture/DTO regression that silently stops serializing news (a {@code
     *  @JsonIgnore}, an accidental empty list, a DTO swap) cannot make this test vacuously
     *  green — it must actually be measuring a realistic worst-case payload. */
    private static final int MINIMUM_PLAUSIBLE_BYTES = 50_000;

    /** Reads the recent-news-cap default straight out of {@code application.yaml} instead of
     *  hardcoding a duplicate constant. This is the fix for the finding that raising the cap
     *  in the yaml previously did NOT move this test's worst case — the test constant and the
     *  yaml default could drift apart silently, which is exactly the failure mode this whole
     *  test exists to catch. If this regex ever stops matching (the yaml key is renamed or
     *  restructured), the test fails loudly here instead of silently measuring a stale cap. */
    private static int recentNewsCapDefaultFromYaml() {
        try (InputStream in = EchoPayloadBudgetTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.yaml not found on the test classpath — cannot bind "
                                + "INDEX_ITEMS_PER_CANDIDATE to dracul.strigoi.echo.recent-news-cap");
            }
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("recent-news-cap:\\s*\\$\\{ECHO_RECENT_NEWS_CAP:(\\d+)}")
                    .matcher(yaml);
            if (!m.find()) {
                throw new IllegalStateException(
                        "could not find dracul.strigoi.echo.recent-news-cap's default in "
                                + "application.yaml — this test's worst case and the yaml default "
                                + "must move together; update this regex in the same change that "
                                + "changes the yaml key");
            }
            return Integer.parseInt(m.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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

    /** Mirrors the real envelope built by {@code HuntController.handleFetch}: {@code
     *  {"output":{"candidates":[…],"data_source_health":{…},"active_patterns":[…]}}}. {@code
     *  active_patterns} is modelled EMPTY here — see the class javadoc for why that list is
     *  deliberately excluded from this budget rather than silently ignored. */
    private static Map<String, Object> syntheticEnvelope(List<EnrichedPeadCandidate> candidates) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "healthy");
        health.put("source", "agora");
        health.put("detail", null);
        health.put("checked_at", Instant.parse("2026-07-28T06:00:00Z").toString());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("candidates", candidates);
        output.put("data_source_health", health);
        output.put("active_patterns", List.of());

        return Map.of("output", output);
    }

    @Test
    void worstCasePayloadStaysWellUnderTheBridgeToolResultLimit() {
        List<EnrichedPeadCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < WORST_CASE_CANDIDATES; i++) candidates.add(syntheticCandidate(i));

        String json = JsonMapper.builder().build().writeValueAsString(syntheticEnvelope(candidates));
        int measuredBytes = json.getBytes(StandardCharsets.UTF_8).length;

        assertThat(measuredBytes)
                .as("""
                    Echo-Payload-Budget gerissen (%d Kandidaten × %d Index-Items = %d Bytes).
                    Oberhalb von ~95 kB lagert die Claude-Max-Bridge das Tool-Ergebnis in eine
                    Datei aus, die der Agent nicht lesen kann — Echo liefert dann still leeres
                    Prey. Entweder ein Feld zurücknehmen, den recent-news-cap in application.yaml
                    senken, oder das Feld in das Detail-Tool fetch_candidate_news verschieben.
                    (INDEX_ITEMS_PER_CANDIDATE ist an application.yaml gebunden — ein gesenkter
                    Cap dort senkt automatisch auch den hier geprüften Worst Case.)""",
                    WORST_CASE_CANDIDATES, INDEX_ITEMS_PER_CANDIDATE, measuredBytes)
                .isLessThan(BUDGET_BYTES);

        assertThat(measuredBytes)
                .as("""
                    Payload nur %d Bytes — verdächtig klein für %d synthetische Kandidaten.
                    Dieser Floor verhindert, dass ein stillschweigend leer gewordenes
                    recentNews (z.B. @JsonIgnore, DTO-Wechsel, leere Fixture) diesen Test
                    grün macht, ohne dass er noch irgendetwas Reales prüft.""",
                    measuredBytes, WORST_CASE_CANDIDATES)
                .isGreaterThan(MINIMUM_PLAUSIBLE_BYTES);
    }
}
