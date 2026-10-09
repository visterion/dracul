package de.visterion.dracul.agent;

import de.visterion.dracul.strigoi.lazarus.BasicFinancials;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** strigoi-tech prompt <-> schema contract (the recurring prompt/schema drift bug class). */
class TechPromptContractTest {

    private static final String SHIPPED_1_0_0_HASH = "p-f49dceaabf68";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String prompt = AgentResources.classpath("prompts/strigoi-tech.md");
    private final JsonNode schema = AgentResources.readSchema(mapper, "schemas/prey-list-tech.json");
    private final PromptArchive archive = new PromptArchive();

    @Test
    void everyItemPropertyIsNamedInThePrompt() {
        List<String> missing = new ArrayList<>();
        for (String section : List.of("prey", "catastrophe_exits")) {
            JsonNode items = schema.path("properties").path(section).path("items");
            for (String property : items.path("properties").propertyNames()) {
                if (!prompt.contains("`" + property + "`")) missing.add(section + "." + property);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void promptNamesTheToolsAndTheCatastropheRuleButNoKillLevel() {
        assertThat(prompt).contains("`fetch_tech_book`").contains("`check_tech_candidate`")
                .contains("`catastrophe_exits`")
                .contains("Price weakness alone is never a catastrophe")
                .doesNotContain("kill_close_below");
    }

    @Test
    void promptInstructsTwelveMonthHorizonAndNoLongerQuotesTheHalfSaleFigure() {
        assertThat(prompt).contains("`12m`").doesNotContain("+30 %");
    }

    @Test
    void onlyPreyIsRequiredAndKillCriteriaStaysMandatory() {
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        assertThat(required).containsExactly("prey");
        List<String> itemRequired = new ArrayList<>();
        schema.path("properties").path("prey").path("items").path("required")
                .forEach(r -> itemRequired.add(r.asString()));
        assertThat(itemRequired).contains("kill_criteria", "symbol", "confidence");
        assertThat(schema.path("properties").path("prey").path("items").path("properties")
                .path("anomalyType").path("const").asString()).isEqualTo("TECH_CONVICTION");
    }

    @Test
    void archivedStrigoiTech100IsRecognisedAsShipped() {
        String archived = PromptDocument.bodyFromClasspath("prompts/archive/strigoi-tech/1.0.0.md");
        assertThat(PromptHashes.hash(archived)).isEqualTo(SHIPPED_1_0_0_HASH);
        String bundled = PromptDocument.bodyFromClasspath("prompts/strigoi-tech.md");
        assertThat(archive.wasShipped("strigoi-tech", archived, bundled)).isTrue();
    }

    @Test
    void everyFundamentalsFieldNamedInThePromptIsARealBasicFinancialsComponent() {
        Set<String> components = Arrays.stream(BasicFinancials.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        List<String> namedInPrompt = List.of("peTtm", "fcfPerShare", "priceToBook",
                "revenueGrowthYoy", "epsGrowthYoy", "grossMargin", "netMargin");
        List<String> missing = new ArrayList<>();
        for (String field : namedInPrompt) {
            if (!prompt.contains("`" + field + "`")) missing.add("not in prompt: " + field);
            if (!components.contains(field)) missing.add("not a BasicFinancials component: " + field);
        }
        assertThat(missing).isEmpty();
    }
}
