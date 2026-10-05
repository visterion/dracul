package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.agent.AgentDefaultProvider;
import de.visterion.dracul.agent.AgentDefinition;
import de.visterion.dracul.agent.AgentResources;
import de.visterion.dracul.agent.PromptDocument;
import de.visterion.dracul.agent.ToolBinding;
import de.visterion.dracul.agent.ToolCatalogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

@Configuration
@ConditionalOnProperty(value = "dracul.strigoi.tech.enabled", havingValue = "true")
class TechDefaults {

    static final String NAME = "strigoi-tech";
    static final String FETCH_BOOK = "fetch_tech_book";
    static final String CHECK_CANDIDATE = "check_tech_candidate";

    @Bean
    TechSettings techSettings(
            @Value("${dracul.strigoi.tech.basket-size:10}") int basketSize,
            @Value("${dracul.strigoi.tech.max-new-per-week:3}") int maxNewPerWeek,
            @Value("${dracul.strigoi.tech.position-pct:0.03}") BigDecimal positionPct,
            @Value("${dracul.strigoi.tech.min-market-cap-usd-millions:20000}") BigDecimal minMarketCap,
            @Value("${dracul.strigoi.tech.reentry-block-days:90}") int reentryBlockDays,
            @Value("${dracul.executor.connection:depot-1}") String executorConnection,
            @Value("${dracul.position.connection:depot-1}") String depotConnection,
            @Value("${dracul.executor.instrument-currency:USD}") String instrumentCurrency) {
        return new TechSettings(basketSize, maxNewPerWeek, positionPct, minMarketCap,
                reentryBlockDays, executorConnection, depotConnection, instrumentCurrency);
    }

    @Bean
    AgentDefaultProvider techDefaultProvider(ObjectMapper mapper,
            @Value("${dracul.strigoi.tech.schedule}") String schedule) {
        JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-tech.json");
        JsonNode empty = AgentResources.parseJson(mapper, "{\"type\":\"object\",\"properties\":{}}");
        JsonNode checkInput = AgentResources.parseJson(mapper, """
                {"type":"object",
                 "properties":{"symbol":{"type":"string","description":"ticker symbol, e.g. a US listing or ADR"}},
                 "required":["symbol"]}
                """);
        // Neither tool may be cached: the book is tonight's state, the candidate check is per symbol.
        var book = new ToolCatalogEntry(FETCH_BOOK,
                "Returns the tech basket: open CONVICTION positions (entry, qty, highest close, "
                        + "current close, P/L %, half-sold, days held, news since the last run), "
                        + "pending basket signals, free slots, new names allowed this ISO week, "
                        + "recently exited names and the notes of the last completion.",
                empty, "/api/strigoi-tech/tools/fetch-book", 60, false, null);
        var check = new ToolCatalogEntry(CHECK_CANDIDATE,
                "Checks one symbol for the tech basket: profile, quote, 52-week range, MA50/MA200, "
                        + "ATR, fundamentals summary, analyst estimates, the last 10 headlines and "
                        + "a code verdict (eligible, reasons, notes).",
                checkInput, "/api/strigoi-tech/tools/check-candidate", 60, false, null);
        return new AgentDefaultProvider() {
            @Override
            public AgentDefinition defaultDefinition() {
                return new AgentDefinition(
                        NAME, "reasoning",
                        PromptDocument.bodyFromClasspath("prompts/strigoi-tech.md"), schema,
                        schedule, 40, 1800,
                        "/api/strigoi-tech/complete",
                        null, null, null, true,
                        List.of(new ToolBinding(FETCH_BOOK, null, null, 0),
                                new ToolBinding(CHECK_CANDIDATE, null, null, 1),
                                new ToolBinding("search", null, null, 2)));
            }

            @Override
            public List<ToolCatalogEntry> catalogEntries() {
                return List.of(book, check);
            }
        };
    }
}
