package de.visterion.dracul.hunting.edgar;

import de.visterion.dracul.hunting.DataSourceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class EdgarFormFourAdapter {

    private final RestClient http;
    private final String archiveBase;

    @Autowired
    public EdgarFormFourAdapter(
            @Value("${dracul.edgar.user-agent}") String userAgent,
            @Value("${dracul.edgar.archive-base:https://www.sec.gov}") String archiveBase) {
        this.http = RestClient.builder()
                .baseUrl("https://efts.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.archiveBase = archiveBase;
    }

    // Test constructor: pre-built RestClient with User-Agent already configured.
    EdgarFormFourAdapter(RestClient http, String archiveBase) {
        this.http = http;
        this.archiveBase = archiveBase;
    }

    public DataSourceResult<Form4Filing> recentFilings(LocalDate from, LocalDate to) {
        JsonNode search;
        try {
            search = http.get()
                    .uri(uri -> uri.path("/LATEST/search-index")
                            .queryParam("forms", "4")
                            .queryParam("dateRange", "custom")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            return DataSourceResult.unavailable("edgar", "edgar: " + e.getMessage());
        }
        if (search == null) return DataSourceResult.healthy("edgar", List.of());
        JsonNode hits = search.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return DataSourceResult.healthy("edgar", List.of());

        List<Form4Filing> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            try {
                parseHit(hit, out);
            } catch (Exception e) {
                // Skip malformed individual filings; continue
            }
        }
        return DataSourceResult.healthy("edgar", out);
    }

    private void parseHit(JsonNode hit, List<Form4Filing> out) throws Exception {
        String id = hit.path("_id").asText("");
        JsonNode source = hit.path("_source");
        var tickersNode = source.path("tickers");
        if (!tickersNode.isArray() || tickersNode.isEmpty()) return;
        String ticker = tickersNode.get(0).asText("").toUpperCase();
        if (ticker.isEmpty()) return;

        String[] parts = id.split(":");
        if (parts.length != 2) return;
        String accession = parts[0];
        String filename = parts[1];
        String accessionNoDashes = accession.replace("-", "");
        // CIK is the first 10 digits of the accession (zero-padded)
        long cik = Long.parseLong(accessionNoDashes.substring(0, Math.min(10, accessionNoDashes.length())));
        String filingUrl = archiveBase + "/Archives/edgar/data/" + cik
                + "/" + accessionNoDashes + "/" + filename;

        String xml;
        try {
            xml = http.get().uri(filingUrl).retrieve().body(String.class);
        } catch (Exception e) {
            return;
        }
        if (xml == null) return;

        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var owners = doc.getElementsByTagName("reportingOwner");
        String filerName = "";
        String filerRole = "";
        if (owners.getLength() > 0) {
            var owner = (org.w3c.dom.Element) owners.item(0);
            var names = owner.getElementsByTagName("rptOwnerName");
            if (names.getLength() > 0) filerName = names.item(0).getTextContent().trim();
            var titles = owner.getElementsByTagName("officerTitle");
            if (titles.getLength() > 0) filerRole = titles.item(0).getTextContent().trim();
        }

        var transactions = doc.getElementsByTagName("nonDerivativeTransaction");
        for (int i = 0; i < transactions.getLength(); i++) {
            var tx = (org.w3c.dom.Element) transactions.item(i);
            String code = textOf(tx, "transactionCode");
            String dateStr = valueOf(tx, "transactionDate");
            String sharesStr = valueOf(tx, "transactionShares");
            String priceStr = valueOf(tx, "transactionPricePerShare");
            if (dateStr.isEmpty() || sharesStr.isEmpty()) continue;
            BigDecimal shares = new BigDecimal(sharesStr);
            BigDecimal price = priceStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(priceStr);
            BigDecimal dollar = shares.multiply(price);
            out.add(new Form4Filing(
                    ticker, filerName, filerRole,
                    LocalDate.parse(dateStr), shares, dollar, code
            ));
        }
    }

    private static String textOf(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String valueOf(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        var inner = ((org.w3c.dom.Element) nodes.item(0)).getElementsByTagName("value");
        return inner.getLength() == 0
                ? nodes.item(0).getTextContent().trim()
                : inner.item(0).getTextContent().trim();
    }
}
