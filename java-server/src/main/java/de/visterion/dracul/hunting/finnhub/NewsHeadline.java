package de.visterion.dracul.hunting.finnhub;

import java.time.Instant;

public record NewsHeadline(String headline, String summary, String source,
                           Instant datetime, String url) {}
