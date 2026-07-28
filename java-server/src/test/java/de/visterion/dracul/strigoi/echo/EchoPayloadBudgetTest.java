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
 *  Index-Items × ~165 B ≈ 1,7 kB/Kandidat; damit liegt die strukturelle Decke bei ~56
 *  Kandidaten gegen das ~95-kB-Bridge-Limit. {@code BUDGET_BYTES = 80_000} liegt unter dieser
 *  95-kB-Decke und nur KNAPP über einem realistischen 45-Kandidaten-Payload (Stand 2026-07-28:
 *  ca. 4 % Marge) — diese Enge ist bewusst, nicht großzügig: wer hier reißt, hat entweder ein
 *  Feld angebaut oder den Cap erhöht, und soll das sofort merken statt erst nach Wochen
 *  komfortabler Marge.
 *
 *  <p><b>Gemessene Baseline: 2026-07-28, siehe Assertion-Failure für den aktuellen Wert.</b>
 *  Dieser Kommentar nennt bewusst KEINEN festen BYTE-Wert mehr (frühere Fassungen liefen der
 *  tatsächlichen Messung nach jeder Fixture-Änderung hinterher) — nur die knapp 4 % Marge oben
 *  sind als Prozentwert genannt. Der Messwert inklusive
 *  Overshoot in Prozent steht live im Assertion-Failure, sobald der Test reißt. Auf dem
 *  Grün-Pfad wird die Marge NICHT ausgegeben (die {@code .as(...)}-Beschreibung rendert nur bei
 *  einem fehlgeschlagenen Assert); wer die aktuelle Marge auf dem Grün-Pfad braucht, muss
 *  {@code BUDGET_BYTES} kurzzeitig senken und den Overshoot ablesen (siehe Report,
 *  "RED/GREEN-Nachweis").
 *
 *  <p><b>{@code INDEX_ITEMS_PER_CANDIDATE} ist an den YAML-DEFAULT gebunden</b>, nicht hart
 *  verdrahtet: der Wert wird zur Testlaufzeit aus dem Default von
 *  {@code dracul.strigoi.echo.recent-news-cap} in {@code application.yaml} gelesen, sodass eine
 *  Cap-Erhöhung IM YAML-DEFAULT automatisch auch den hier geprüften Worst Case anhebt — vorher
 *  war das ein zahnloses Duplikat, das bei einer Cap-Erhöhung in der Yaml stillschweigend falsch
 *  geworden wäre. <b>Diese Bindung deckt NICHT den Weg ab, über den Prod-Tunables normalerweise
 *  gesetzt werden:</b> per Umgebungsvariable im Deploy-Compose, niemals im Repo. Eine {@code
 *  ECHO_RECENT_NEWS_CAP}-Env-Var, die den Yaml-Default zur Laufzeit überschreibt, umgeht diesen
 *  Test vollständig — der Build bliebe grün, obwohl der reale Cap gestiegen wäre. Das ist eine
 *  dokumentierte strukturelle Lücke (kein Zugriff auf die Deploy-Umgebung zur Testzeit), aber
 *  NICHT die Ursache des Ausfalls vom 2026-07-22: der wurde durch Commit {@code 5b86dba1}
 *  ("feat(sentiment): surface capped recentNews to echo", 2026-07-19) verursacht, der den
 *  Pro-Kandidat-News-Block (damals inklusive Summary) und einen REPO-COMMITTETEN Yaml-Default
 *  von 10 einführte — beides Änderungen, die dieses Feld-Messverfahren und diese Yaml-Bindung
 *  tatsächlich abdecken. Es gibt keinen Beleg, dass {@code ECHO_RECENT_NEWS_CAP} je in einer
 *  Deploy-Umgebung gesetzt war. Diese Bindung schützt ausschließlich vor Drift zwischen
 *  Repo-Test und Repo-Default — nicht vor einem Prod-Override, der (Stand heute) nicht die
 *  reale Ursache war.
 *
 *  <p><b>Was NICHT gemessen wird — {@code active_patterns}:</b> die reale Bridge-Antwort ist
 *  nicht die nackte Kandidatenliste, sondern die Envelope aus
 *  {@code HuntController.handleFetch}: {@code {"output":{"candidates":[…],
 *  "data_source_health":{…},"active_patterns":[…]}}}. Envelope + {@code data_source_health}
 *  sind mit ~200 B vernachlässigbar und werden unten nachgebildet. {@code active_patterns}
 *  (aus {@code PatternRepository.findAcceptedByStrigoi}) ist dagegen eine UNGEDECKELTE Liste
 *  von TEXT-Statements (~200 B je Eintrag laut Seed-Daten, 135–235 Zeichen), die mit jedem vom
 *  Lernloop akzeptierten Pattern monotonisch wächst UND absichtlich mit {@code 'all'}-Mustern
 *  aller Hunter geteilt wird. {@code V2__seed.sql} liefert bereits auf einer frischen Datenbank
 *  DREI ACTIVE {@code strigoi-echo}-Patterns (~460 Zeichen zusammen) — das ist die reale
 *  Baseline, NICHT null. Die Fixture unten bildet diese drei Seed-Patterns mit synthetischen
 *  Platzhaltern gleicher Größenordnung nach, damit die gemessene Baseline diesen bereits
 *  verbrauchten Anteil der Marge enthält. NICHT nachgebildet wird das WEITERE Wachstum über
 *  diese drei Seed-Patterns hinaus — das bleibt absichtlich außerhalb dieses Budgets. Bei
 *  ~200 B/zusätzlichem Pattern verbraucht ein Zuwachs von etwa 90 weiteren, echo-relevanten
 *  Patterns bereits die gesamte verbleibende Marge zwischen der gemessenen Baseline und dem
 *  realen ~95-kB-Bridge-Limit — unabhängig vom Kandidaten-Budget hier. Weiteres
 *  Pattern-Wachstum braucht eine EIGENE Absicherung (z.B. einen Cap in
 *  {@code findAcceptedByStrigoi} oder einen eigenen Regressionstest); dieser Test hier deckt
 *  ausschließlich den Kandidaten-Teil plus die drei Seed-Patterns als Baseline ab.
 *
 *  <p><b>Bekannte, akzeptierte Lücke:</b> dieser Test deckt Kandidatenzahlen bis 45 ab — ab 46
 *  Kandidaten prüft er nichts mehr. Der reale strukturelle Schaden (Bridge-Limit gerissen)
 *  setzt aber erst deutlich später ein, ungefähr ab ~56 Kandidaten (siehe Kalibrierung oben).
 *  Der Bereich 46–55 ist also ungetestet, aber (Stand heute) noch nicht schädlich — das ist
 *  eine dokumentierte, bewusst akzeptierte Lücke, kein Versehen, und KEINE Aussage, dass dieser
 *  Test bis 55 abdeckt. Sollte die reale Kandidatenzahl je in die Nähe von ~56 wachsen, muss
 *  dieser Test neu kalibriert werden (Kandidatenzahl serverseitig deckeln, recent-news-cap
 *  senken, oder ein Feld aus dem Index in {@code fetch_candidate_news} verschieben).
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

    /** Reads the recent-news-cap default straight out of the {@code dracul.strigoi.echo}
     *  section of {@code application.yaml} instead of hardcoding a duplicate constant. This is
     *  the fix for the finding that raising the cap in the yaml previously did NOT move this
     *  test's worst case — the test constant and the yaml default could drift apart silently,
     *  which is exactly the failure mode this whole test exists to catch (see the class
     *  javadoc for the one path this binding still cannot see: an {@code
     *  ECHO_RECENT_NEWS_CAP} override at deploy time). The lookup is anchored to the {@code
     *  echo:} block specifically (not a bare, unanchored "recent-news-cap:" search) so a future
     *  hunter reusing the same YAML key name cannot make this silently bind to the wrong
     *  section; it also fails loudly if it finds anything other than exactly one match, instead
     *  of silently taking the first one. If this regex ever stops matching (the yaml key or
     *  section is renamed or restructured), the test fails loudly here instead of silently
     *  measuring a stale cap. */
    private static int recentNewsCapDefaultFromYaml() {
        try (InputStream in = EchoPayloadBudgetTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.yaml not found on the test classpath — cannot bind "
                                + "INDEX_ITEMS_PER_CANDIDATE to dracul.strigoi.echo.recent-news-cap");
            }
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            Matcher section = Pattern.compile("(?m)^    echo:\\n(.*?)(?=^    \\S)", Pattern.DOTALL)
                    .matcher(yaml);
            if (!section.find()) {
                throw new IllegalStateException(
                        "could not locate the 'dracul.strigoi.echo:' section in application.yaml "
                                + "(expected a 4-space-indented 'echo:' key under 'strigoi:') — "
                                + "update this anchor in the same change that restructures the yaml");
            }
            String echoBlock = section.group(1);

            Matcher m = Pattern.compile("recent-news-cap:\\s*\\$\\{ECHO_RECENT_NEWS_CAP:(\\d+)}")
                    .matcher(echoBlock);
            if (!m.find()) {
                throw new IllegalStateException(
                        "could not find dracul.strigoi.echo.recent-news-cap's default inside the "
                                + "echo: section of application.yaml — this test's worst case and "
                                + "the yaml default must move together; update this regex in the "
                                + "same change that changes the yaml key");
            }
            int cap = Integer.parseInt(m.group(1));
            if (m.find()) {
                throw new IllegalStateException(
                        "found more than one recent-news-cap match inside the echo: section of "
                                + "application.yaml — ambiguous binding, fix this regex before "
                                + "trusting the derived worst case");
            }
            return cap;
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

    /** Stand-ins for the 3 ACTIVE {@code strigoi-echo} patterns {@code V2__seed.sql} ships on a
     *  fresh database (~462 real characters total) — SYNTHETIC text of equivalent length, not
     *  the seeded statements themselves. This is the real baseline
     *  {@code PatternRepository.findAcceptedByStrigoi("strigoi-echo")} returns on day one, not
     *  an empty list; growth BEYOND these three stays deliberately out of this budget (see the
     *  class javadoc). */
    private static final List<String> SEED_BASELINE_ACTIVE_PATTERNS = List.of(
            "SYNTHETIC placeholder pattern: signals arriving near a defined calendar boundary "
                    + "show reduced follow-through in the synthetic backtest data set used for fixtures.",
            "SYNTHETIC placeholder pattern: effect strength scales with a secondary synthetic "
                    + "covariate observed across multiple fixture-only backtest scenarios and windows.",
            "SYNTHETIC placeholder pattern: effect is most pronounced within a synthetic "
                    + "capitalization band where fixture coverage density is deliberately lower than average.");

    /** Mirrors the real envelope built by {@code HuntController.handleFetch}: {@code
     *  {"output":{"candidates":[…],"data_source_health":{…},"active_patterns":[…]}}}. {@code
     *  active_patterns} is modelled with {@link #SEED_BASELINE_ACTIVE_PATTERNS} — the real
     *  fresh-database baseline, not an empty list — see the class javadoc for why growth beyond
     *  that baseline is deliberately excluded from this budget rather than silently ignored. */
    private static Map<String, Object> syntheticEnvelope(List<EnrichedPeadCandidate> candidates) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "healthy");
        health.put("source", "agora");
        health.put("detail", null);
        health.put("checked_at", Instant.parse("2026-07-28T06:00:00Z").toString());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("candidates", candidates);
        output.put("data_source_health", health);
        output.put("active_patterns", SEED_BASELINE_ACTIVE_PATTERNS);

        return Map.of("output", output);
    }

    @Test
    void worstCasePayloadStaysWellUnderTheBridgeToolResultLimit() {
        List<EnrichedPeadCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < WORST_CASE_CANDIDATES; i++) candidates.add(syntheticCandidate(i));

        String json = JsonMapper.builder().build().writeValueAsString(syntheticEnvelope(candidates));
        int measuredBytes = json.getBytes(StandardCharsets.UTF_8).length;
        double marginPercent = (BUDGET_BYTES - measuredBytes) * 100.0 / BUDGET_BYTES;

        assertThat(measuredBytes)
                .as("""
                    Echo-Payload-Budget gerissen (%d Kandidaten × %d Index-Items = %d Bytes,
                    Budget %d Bytes, Marge %.1f %%).
                    Oberhalb von ~95 kB lagert die Claude-Max-Bridge das Tool-Ergebnis in eine
                    Datei aus, die der Agent nicht lesen kann — Echo liefert dann still leeres
                    Prey. Entweder ein Feld zurücknehmen, den recent-news-cap in application.yaml
                    senken, oder das Feld in das Detail-Tool fetch_candidate_news verschieben.
                    (INDEX_ITEMS_PER_CANDIDATE ist an den Yaml-Default gebunden — ein gesenkter
                    Cap dort senkt automatisch auch den hier geprüften Worst Case; ein
                    ECHO_RECENT_NEWS_CAP-Override in der Deploy-Umgebung umgeht diesen Test.)""",
                    WORST_CASE_CANDIDATES, INDEX_ITEMS_PER_CANDIDATE, measuredBytes,
                    BUDGET_BYTES, marginPercent)
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
