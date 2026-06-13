package de.visterion.dracul.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${dracul.cors.allowed-origins:http://localhost:5173}") String origins) {
        this.allowedOrigins = parseOrigins(origins);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        // Browsers attach an Origin header to state-changing requests (POST/PUT/PATCH/DELETE)
        // even when same-origin, so Spring runs its CORS check on them and rejects any origin
        // not in this list with 403 "Invalid CORS request"; GET same-origin requests carry no
        // Origin and skip the check. In prod the SPA is served same-origin as the API from the
        // public browser domain, which must be listed here (NOT dracul.public-url — that is the
        // internal http://dracul:8080 webhook URL, not the browser origin).
        String[] origins = allowedOrigins;
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowCredentials(true);
            }
        };
    }

    /** Split a comma-separated origin list, trimming blanks. */
    static String[] parseOrigins(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
