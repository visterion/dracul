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

/** Regressionsanker für das Tool-Result-Limit der Claude-Max-Bridge. Am 2026-07-22 riss der
 *  Echo-Payload dieses Limit; die Bridge lagerte das Ergebnis in eine Datei aus, die der Agent
 *  nicht lesen kann, und Echo lieferte 7 Tage lang leeres Prey OHNE Fehler — kein Log, kein
 *  Alarm, nur ein leerer Ergebnisstrom. Dieser Test macht ein erneutes Überschreiten im Build
 *  sichtbar statt erst nach Tagen stiller Leere.
 *
 *  <p><b>Das Limit — die echten Zahlen (2026-08-04 nachgemessen, ersetzt die alte
 *  „~95 kB"-Folklore):</b> die Bridge deckelt ein MCP-Tool-Ergebnis bei
 *  {@code MAX_MCP_OUTPUT_TOKENS = 25 000} Token. Eine billige Vorprüfung (Token ≈
 *  Zeichen/4) überspringt jede Kürzung, solange das Ergebnis ≤ 25 000 × 0,5 = 12 500 Token,
 *  also <b>≤ 50 000 Zeichen</b> ist. Darüber läuft der echte Tokenizer, und > 25 000 Token
 *  werden auf <b>100 000 Zeichen</b> hart geschnitten. 50 000 Zeichen sind damit die einzige
 *  GARANTIERT sichere Zone — und deshalb {@code BUDGET_BYTES}. Die alten 80 000 lagen in dem
 *  Bereich, in dem das Ergebnis vom echten Tokenizer abhängt, also im Unbekannten.
 *
 *  <p><b>Kalibrierung (2026-08-04, an einem echten Prod-Payload gemessen).</b> Produktionslauf
 *  vom 2026-08-04, Tool {@code fetch_recent_pead_candidates}: 29 Kandidaten = 45 106 B kompakt,
 *  davon {@code recentNews} <b>21 427 B = 47,5 %</b> (131 Items à 160 B, Ø 4,5 Items je
 *  Kandidat); Grundkosten also ~835 B je Kandidat. Genau wie beim Merger-Term-Sheet war der
 *  richtige Hebel die Größe PRO Kandidat, nicht die Kandidatenzahl: der News-INDEX wurde von 5
 *  auf 3 Items gesenkt (kein Datenverlust — {@code newsCount} nennt weiterhin die echte
 *  Gesamtzahl, und {@code fetch_candidate_news} liefert bis zu 40 Items MIT Summary für jedes
 *  Symbol, das der Agent wirklich lesen will). Mit 5 Index-Items hätte der Cap auf 28 fallen
 *  müssen — unter die 29 Kandidaten eines echten Earnings-Tages, also echte Feature-Reduktion.
 *  Mit 3 Items trägt {@code max-candidates} = 33 (gemessen 45 806 B, Reserve 4 194 B ≈ 21
 *  weitere akzeptierte Patterns).
 *
 *  <p><b>{@code WORST_CASE_CANDIDATES} und {@code INDEX_ITEMS_PER_CANDIDATE} sind an die
 *  YAML-DEFAULTS gebunden</b> ({@code dracul.strigoi.echo.max-candidates} bzw.
 *  {@code recent-news-cap}), nicht hart verdrahtet. Der Test misst damit einen Payload, den der
 *  Code tatsächlich erzeugen kann, und eine Cap-Erhöhung im Yaml bricht den Bau in dem Moment,
 *  in dem sie die sichere Zone verlässt. <b>Diese Bindung deckt NICHT den Weg ab, über den
 *  Prod-Tunables normalerweise gesetzt werden:</b> per Umgebungsvariable im Deploy-Compose,
 *  niemals im Repo. Eine {@code ECHO_MAX_CANDIDATES}- oder {@code ECHO_RECENT_NEWS_CAP}-Env-Var
 *  umgeht diesen Test vollständig — eine dokumentierte strukturelle Lücke (kein Zugriff auf die
 *  Deploy-Umgebung zur Testzeit), aber NICHT die Ursache des Ausfalls vom 2026-07-22: der kam
 *  aus Commit {@code 5b86dba1}, der einen REPO-COMMITTETEN Yaml-Default einführte — genau das,
 *  was diese Bindung sieht.
 *
 *  <p><b>Was NICHT gemessen wird — Wachstum von {@code active_patterns}:</b> die reale
 *  Bridge-Antwort ist die Envelope aus {@code HuntController.handleFetch}:
 *  {@code {"output":{"candidates":[…],"data_source_health":{…},"active_patterns":[…]}}}.
 *  Envelope + {@code data_source_health} (~250 B) und die DREI ACTIVE
 *  {@code strigoi-echo}-Patterns, die {@code V2__seed.sql} auf einer frischen Datenbank liefert
 *  (~460 Zeichen; die reale Baseline, NICHT null), sind unten nachgebildet und stecken im
 *  gemessenen Wert. {@code active_patterns} ist aber eine UNGEDECKELTE Liste, die mit jedem vom
 *  Lernloop akzeptierten Pattern wächst (~200 B je Eintrag) und absichtlich über
 *  {@code 'all'}-Muster mit anderen Huntern geteilt wird. Das Wachstum darüber hinaus bleibt
 *  bewusst außerhalb dieses Budgets: die verbleibende Marge zwischen dem gemessenen Wert und
 *  {@code BUDGET_BYTES} IST diese Reserve (Stand 2026-08-04: 4 194 B ≈ 21 weitere Patterns).
 *  Weiteres Wachstum braucht eine EIGENE Absicherung (z. B. einen Cap in
 *  {@code findAcceptedByStrigoi}).
 *
 *  <p>Alle Werte sind SYNTHETISCH und an der Ø realer Prod-Werte kalibriert
 *  ({@code example.com} als offensichtlich synthetische Quelle nach RFC 2606). Nichts stammt
 *  aus Produktionsdaten. */
class EchoPayloadBudgetTest {

    private static final int WORST_CASE_CANDIDATES = maxCandidatesDefaultFromYaml();
    private static final int INDEX_ITEMS_PER_CANDIDATE = recentNewsCapDefaultFromYaml();
    private static final int BUDGET_BYTES = 50_000;
    /** Floor so a fixture/DTO regression that silently stops serializing news (a {@code
     *  @JsonIgnore}, an accidental empty list, a DTO swap) cannot make this test vacuously
     *  green — it must actually be measuring a realistic worst-case payload. */
    private static final int MINIMUM_PLAUSIBLE_BYTES = 30_000;

    /** Reads the candidate cap default out of the same {@code echo:} block, by the same
     *  anchored-and-unambiguous method as {@link #recentNewsCapDefaultFromYaml()}. Binding the
     *  worst case to the CONFIGURED cap rather than to a hand-picked number is the whole point:
     *  the test then measures a payload the code can actually produce, and raising the cap in
     *  the yaml fails the build the moment it leaves the safe zone. */
    private static int maxCandidatesDefaultFromYaml() {
        return echoIntDefaultFromYaml(
                "max-candidates:\\s*\\$\\{ECHO_MAX_CANDIDATES:(\\d+)}", "max-candidates");
    }

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
        return echoIntDefaultFromYaml(
                "recent-news-cap:\\s*\\$\\{ECHO_RECENT_NEWS_CAP:(\\d+)}", "recent-news-cap");
    }

    /** Shared, anchored lookup of an integer default inside the {@code dracul.strigoi.echo:}
     *  block of {@code application.yaml}. Anchoring to the {@code echo:} section (rather than an
     *  unanchored key search) stops a future hunter reusing the same key name from silently
     *  rebinding this; more or fewer than exactly one match fails loudly instead of quietly
     *  taking the first. */
    private static int echoIntDefaultFromYaml(String keyRegex, String what) {
        try (InputStream in = EchoPayloadBudgetTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.yaml not found on the test classpath — cannot bind the "
                                + "worst case to dracul.strigoi.echo." + what);
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

            Matcher m = Pattern.compile(keyRegex).matcher(echoBlock);
            if (!m.find()) {
                throw new IllegalStateException(
                        "could not find dracul.strigoi.echo." + what + "'s default inside the "
                                + "echo: section of application.yaml — this test's worst case and "
                                + "the yaml default must move together; update this regex in the "
                                + "same change that changes the yaml key");
            }
            int value = Integer.parseInt(m.group(1));
            if (m.find()) {
                throw new IllegalStateException(
                        "found more than one " + what + " match inside the echo: section of "
                                + "application.yaml — ambiguous binding, fix this regex before "
                                + "trusting the derived worst case");
            }
            return value;
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
                    Oberhalb von 50 000 Zeichen entscheidet der echte Tokenizer der
                    Claude-Max-Bridge, ob gekürzt wird (harte Decke 100 000); ein gekürztes
                    Ergebnis landet in einer Datei, die der Agent nicht lesen kann — Echo
                    liefert dann still leeres Prey. Reihenfolge der Hebel: ZUERST die Größe pro
                    Kandidat senken (recent-news-cap, ein Feld in das Detail-Tool
                    fetch_candidate_news verschieben), erst DANN max-candidates — ein kleinerer
                    Cap ist Feature-Reduktion, ein schlankerer Index nicht.
                    (Beide Werte sind an ihre Yaml-Defaults gebunden; ein ECHO_MAX_CANDIDATES-
                    oder ECHO_RECENT_NEWS_CAP-Override in der Deploy-Umgebung umgeht diesen
                    Test.)""",
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
