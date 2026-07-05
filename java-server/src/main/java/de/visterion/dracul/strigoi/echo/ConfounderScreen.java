package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.finnhub.NewsHeadline;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Confounder screen over company news since the report date. Scans each headline+summary for
 * keyword categories that would make the announcement-CAR something other than the earnings
 * drift signal (M&A, restatement, guidance cut, dilution/offering, investigation). Relocated
 * from the deleted FinnhubEventScreen — the news fetch is Agora's, the keyword interpretation
 * is Dracul's. Never throws (the facade never throws); empty = clean.
 */
@Component
public class ConfounderScreen {

    private record Category(String flag, String[] keywords) {}

    private static final List<Category> CATEGORIES = List.of(
            new Category("m&a", new String[]{"merger", "acquisition", "to acquire", "acquire ", "acquires", "buyout", "takeover"}),
            new Category("restatement", new String[]{"restate", "restatement"}),
            new Category("guidance-cut", new String[]{"cuts guidance", "lowers guidance", "cuts forecast",
                    "lowers outlook", "guidance cut", "slashes forecast"}),
            new Category("dilution", new String[]{"public offering", "share offering", "stock offering", "dilution",
                    "secondary offering"}),
            new Category("investigation", new String[]{"sec investigation", "sec probe", "fraud"}));

    private final AgoraCompanyData companyData;

    public ConfounderScreen(AgoraCompanyData companyData) { this.companyData = companyData; }

    public List<String> confounders(String symbol, LocalDate since) {
        Set<String> flags = new LinkedHashSet<>();
        for (NewsHeadline h : companyData.news(symbol, since, LocalDate.now())) {
            String text = ((h.headline() == null ? "" : h.headline()) + " "
                    + (h.summary() == null ? "" : h.summary())).toLowerCase(Locale.ROOT);
            for (Category c : CATEGORIES) {
                for (String kw : c.keywords()) {
                    if (text.contains(kw)) { flags.add(c.flag()); break; }
                }
            }
        }
        return new ArrayList<>(flags);
    }
}
