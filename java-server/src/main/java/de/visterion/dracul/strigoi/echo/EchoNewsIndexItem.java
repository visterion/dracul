package de.visterion.dracul.strigoi.echo;

import java.time.Instant;

/** Ein Eintrag im schlanken News-INDEX des Kandidaten-Payloads. Bewusst OHNE {@code summary}:
 *  der Volltext kostete 44 % des Payloads und riss das Tool-Result-Limit der Bridge (Spec
 *  2026-07-27). Wer den Volltext braucht, ruft {@code fetch_candidate_news} für dieses Symbol
 *  — dort liefert {@link EchoNewsItem} die vollen Felder. */
public record EchoNewsIndexItem(String headline, String source, double credibility, Instant datetime) {}
