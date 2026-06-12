package de.visterion.dracul.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One-time, idempotent reassignment of legacy watchlist rows owned by the sentinel
 * "default" user to the configured primary user, so existing data is not orphaned
 * once the watchlist becomes per-owner. No-op when DRACUL_PRIMARY_USER_EMAIL is blank.
 */
@Configuration
public class LegacyWatchlistOwnerMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyWatchlistOwnerMigration.class);

    @Bean
    ApplicationRunner reassignDefaultWatchlistOwner(
            JdbcClient jdbc,
            @Value("${dracul.primary-user-email:}") String primaryEmail) {
        return args -> {
            if (primaryEmail.isBlank()) return;
            int n = jdbc.sql("UPDATE watchlist_items SET user_id = :email WHERE user_id = 'default'")
                    .param("email", primaryEmail).update();
            if (n > 0) log.info("Reassigned {} legacy 'default' watchlist items to {}", n, primaryEmail);
        };
    }
}
