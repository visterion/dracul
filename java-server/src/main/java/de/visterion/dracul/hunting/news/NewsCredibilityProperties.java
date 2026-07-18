package de.visterion.dracul.hunting.news;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Static operator table for news credibility scoring (T1.4). {@code sources} is an
 * ORDERED list — first hit wins (config order = precedence); {@code match} is compared
 * exactly and case-insensitively against a headline's url domain and its source string.
 * No enable flag: an empty table means everything scores {@code defaultScore} and
 * nothing drops — the table itself is the switch. All scores fail fast at startup
 * when outside [0,1]; {@code defaultScore < dropBelow} also fails fast (it would
 * silently hard-drop every unknown source and darken the news trigger path).
 */
@ConfigurationProperties(prefix = "dracul.news.credibility")
@Validated
public record NewsCredibilityProperties(
        @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double defaultScore,
        @DefaultValue("0.3") @DecimalMin("0.0") @DecimalMax("1.0") double dropBelow,
        @Valid List<SourceEntry> sources) {

    public record SourceEntry(String match,
            @DecimalMin("0.0") @DecimalMax("1.0") double score) {}

    public NewsCredibilityProperties {
        if (sources == null) sources = List.of();
    }

    @AssertTrue(message = "dracul.news.credibility.default-score must be >= drop-below — "
            + "otherwise every unknown source is silently hard-dropped")
    public boolean isDefaultScoreAtLeastDropBelow() {
        return defaultScore >= dropBelow;
    }
}
