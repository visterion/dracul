package de.visterion.dracul;

import de.visterion.dracul.hunting.news.NewsCredibilityProperties;
import de.visterion.dracul.vistierie.VistierieProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({VistierieProperties.class, NewsCredibilityProperties.class})
public class DrakulApplication {
    public static void main(String[] args) {
        SpringApplication.run(DrakulApplication.class, args);
    }
}
