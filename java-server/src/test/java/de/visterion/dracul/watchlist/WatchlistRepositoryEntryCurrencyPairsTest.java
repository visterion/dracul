package de.visterion.dracul.watchlist;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for {@link WatchlistRepository#distinctEntryCurrencyPairs()} — the SQL
 * exclusion ({@code upper(entry_currency) <> upper(currency)} plus the two NOT NULL checks) is
 * never exercised by the mock-level {@code FxRateRefresherTest}, so a typo in the query (e.g.
 * {@code <>} flipped to {@code =}, or a wrong column reference) would not be caught there.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistRepositoryEntryCurrencyPairsTest {

    @Autowired WatchlistRepository repo;

    private static final String OWNER = "alice@example.com";

    @Test
    void returnsPairForHeldRowWithDifferentEntryAndQuoteCurrency() {
        var item = repo.insert(OWNER, "PAIRCO", "Pair Co", 223.93, List.of(223.93),
                "HELD", "manual", null, "USD");
        repo.updatePosition(item.id(), 162.20, 10.0, "EUR");

        assertThat(repo.distinctEntryCurrencyPairs())
                .contains(new WatchlistRepository.CurrencyPair("EUR", "USD"));
    }

    @Test
    void excludesRowsWhereEntryCurrencyEqualsCurrencyCaseInsensitive() {
        // same currency, matching case
        var same = repo.insert(OWNER, "SAMECO", "Same Co", 50.0, List.of(50.0),
                "HELD", "manual", null, "GBP");
        repo.updatePosition(same.id(), 45.0, 3.0, "GBP");

        // same currency, differing case — the upper() comparison must still exclude it
        var mixedCase = repo.insert(OWNER, "MIXEDCASECO", "Mixed Case Co", 30.0, List.of(30.0),
                "HELD", "manual", null, "GBP");
        repo.updatePosition(mixedCase.id(), 28.0, 2.0, "gbp");

        // general invariant: a same-currency row must never surface as a "pair" to convert
        assertThat(repo.distinctEntryCurrencyPairs())
                .noneMatch(p -> p.from().equalsIgnoreCase(p.to()));
    }

    @Test
    void excludesRowsWithNullEntryCurrencyOrCurrency() {
        // currency IS NULL, entry_currency set directly via updatePosition
        var nullCurrency = repo.insert(OWNER, "NULLCURR", "Null Currency Co", 10.0, List.of(10.0),
                "HELD", "manual", null, null);
        repo.updatePosition(nullCurrency.id(), 9.0, 1.0, "NUL1");

        // currency set, entry_currency left NULL (no updatePosition call)
        repo.insert(OWNER, "NULLENTRYCURR", "Null Entry Currency Co", 20.0, List.of(20.0),
                "HELD", "manual", null, "NUL2");

        var pairs = repo.distinctEntryCurrencyPairs();
        assertThat(pairs).noneMatch(p -> "NUL1".equalsIgnoreCase(p.from()));
        assertThat(pairs).noneMatch(p -> "NUL2".equalsIgnoreCase(p.to()));
    }
}
