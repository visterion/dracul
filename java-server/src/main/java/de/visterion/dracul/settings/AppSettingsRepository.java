package de.visterion.dracul.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppSettingsRepository {

    private static final String LANGUAGE_KEY = "language";
    private static final String DEFAULT_LANGUAGE = "de";

    private final JdbcClient jdbc;

    public AppSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String getLanguage() {
        return jdbc.sql("SELECT value FROM app_settings WHERE key = :key")
                .param("key", LANGUAGE_KEY)
                .query(String.class)
                .optional()
                .orElse(DEFAULT_LANGUAGE);
    }

    public void setLanguage(String language) {
        jdbc.sql("""
                INSERT INTO app_settings (key, value, updated_at)
                VALUES (:key, :value, now())
                ON CONFLICT (key) DO UPDATE SET value = :value, updated_at = now()
                """)
                .param("key", LANGUAGE_KEY)
                .param("value", language)
                .update();
    }
}
