package de.visterion.dracul.hunting.agora;

import java.time.Instant;

/**
 * One company-news item from Agora's get_company_news. {@code sourceType} is a media-type
 * label ("news" | "social"); items from an older Agora without the field default to "news".
 */
public record NewsHeadline(String headline, String summary, String source,
                           String sourceType, Instant datetime, String url) {}
