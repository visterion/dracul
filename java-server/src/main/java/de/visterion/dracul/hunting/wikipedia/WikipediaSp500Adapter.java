package de.visterion.dracul.hunting.wikipedia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wikipedia "List of S&P 500 companies" adapter. Fetches the page wikitext via the
 * MediaWiki API and parses the main constituents table (Symbol + Date added
 * columns). Metadata-only, no HTML-parser dependency. Graceful degradation: any
 * fetch/parse failure or a table-not-found returns an empty list and logs a
 * warning — the bee never dies on a Wikipedia hiccup or a markup change.
 */
@Component
public class WikipediaSp500Adapter {

    private static final Logger log = LoggerFactory.getLogger(WikipediaSp500Adapter.class);
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern REF = Pattern.compile("<ref.*?(</ref>|/>)");

    private final RestClient http;
    private final String pageTitle;

    @Autowired
    public WikipediaSp500Adapter(
            @Value("${dracul.wikipedia.base-url:https://en.wikipedia.org}") String baseUrl,
            @Value("${dracul.wikipedia.user-agent:Dracul/1.0 (research)}") String userAgent,
            @Value("${dracul.wikipedia.sp500-page:List of S&P 500 companies}") String pageTitle) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.pageTitle = pageTitle;
    }

    // Test constructor: pre-built RestClient + page title.
    WikipediaSp500Adapter(RestClient http, String pageTitle) {
        this.http = http;
        this.pageTitle = pageTitle;
    }

    public List<Sp500Constituent> recentConstituents() {
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/w/api.php")
                            .queryParam("action", "parse")
                            .queryParam("page", pageTitle)
                            .queryParam("prop", "wikitext")
                            .queryParam("format", "json")
                            .queryParam("formatversion", "2")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Wikipedia S&P 500 fetch failed: {}", e.getMessage());
            return List.of();
        }
        if (body == null) return List.of();
        JsonNode wt = body.path("parse").path("wikitext");
        if (!wt.isTextual()) return List.of();
        try {
            return parse(wt.asText());
        } catch (Exception e) {
            log.warn("Wikipedia S&P 500 parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Sp500Constituent> parse(String wikitext) {
        int dateHdr = wikitext.indexOf("Date added");
        if (dateHdr < 0) return List.of();
        int tableStart = wikitext.lastIndexOf("{|", dateHdr);
        int tableEnd = wikitext.indexOf("|}", dateHdr);
        if (tableStart < 0 || tableEnd < 0 || tableEnd <= tableStart) return List.of();
        String block = wikitext.substring(tableStart, tableEnd);

        String[] rows = block.split("\\n\\|-");
        if (rows.length < 2) return List.of();

        List<String> headers = cells(rows[1], '!');
        int symIdx = headerIndex(headers, "Symbol", 0);
        int comIdx = headerIndex(headers, "Security", 1);
        int dateIdx = headerIndex(headers, "Date added", -1);
        if (dateIdx < 0) return List.of();

        List<Sp500Constituent> out = new ArrayList<>();
        for (int i = 2; i < rows.length; i++) {
            List<String> c = cells(rows[i], '|');
            if (dateIdx >= c.size() || symIdx >= c.size()) continue;
            LocalDate d = parseDate(c.get(dateIdx));
            if (d == null) continue;
            String sym = stripWiki(c.get(symIdx));
            if (sym.isEmpty()) continue;
            String com = comIdx < c.size() ? stripWiki(c.get(comIdx)) : "";
            out.add(new Sp500Constituent(sym, com, d));
        }
        return out;
    }

    /** Split a wikitext row segment into cell texts. Handles per-line `| cell` and inline `|| `. */
    private static List<String> cells(String segment, char marker) {
        String norm = segment.replace("" + marker + marker, "\n" + marker);
        List<String> out = new ArrayList<>();
        for (String line : norm.split("\\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.charAt(0) != marker) continue;
            out.add(t.substring(1).strip());
        }
        return out;
    }

    private static int headerIndex(List<String> headers, String name, int fallback) {
        for (int i = 0; i < headers.size(); i++) {
            if (stripWiki(headers.get(i)).equalsIgnoreCase(name)) return i;
        }
        return fallback;
    }

    private static LocalDate parseDate(String cell) {
        if (cell == null) return null;
        Matcher m = ISO_DATE.matcher(cell);
        if (!m.find()) return null;
        try { return LocalDate.parse(m.group(1)); } catch (Exception e) { return null; }
    }

    private static String stripWiki(String cell) {
        if (cell == null) return "";
        String s = REF.matcher(cell).replaceAll("").trim();
        Matcher m = WIKILINK.matcher(s);
        if (m.find()) {
            String inner = m.group(1);
            int pipe = inner.indexOf('|');
            s = pipe >= 0 ? inner.substring(pipe + 1) : inner;
        }
        return s.trim();
    }
}
