package de.visterion.dracul.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;

@Configuration
public class CorsConfig {

    private final String publicOrigin;

    public CorsConfig(@Value("${dracul.public-url:http://localhost:8080}") String publicUrl) {
        this.publicOrigin = originOf(publicUrl);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        // Browsers attach an Origin header to state-changing requests (POST/PUT/PATCH/DELETE)
        // even when same-origin, so Spring runs its CORS check on them; GET same-origin
        // requests carry no Origin and skip the check. In prod the SPA is served same-origin
        // as the API from dracul.public-url, so that origin must be allowed or every write
        // is rejected with 403 "Invalid CORS request". localhost:5173 stays for the dev SPA.
        String prodOrigin = publicOrigin;
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173", prodOrigin)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowCredentials(true);
            }
        };
    }

    /** Reduce a base URL to its scheme://host[:port] origin; falls back to the input on parse failure. */
    static String originOf(String url) {
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || u.getHost() == null) return url;
            String origin = u.getScheme() + "://" + u.getHost();
            if (u.getPort() != -1) origin += ":" + u.getPort();
            return origin;
        } catch (RuntimeException e) {
            return url;
        }
    }
}
