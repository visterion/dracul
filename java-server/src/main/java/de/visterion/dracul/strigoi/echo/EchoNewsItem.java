package de.visterion.dracul.strigoi.echo;

import java.time.Instant;

/** One post-report headline surfaced to the Echo LLM for sentiment scoring (T1.5 spec §5.3).
 *  Deliberately narrower than {@link de.visterion.dracul.hunting.agora.NewsHeadline} — no
 *  sourceType/url/domain noise in the fetch-candidates payload the LLM reads. */
public record EchoNewsItem(String headline, String summary, String source, double credibility, Instant datetime) {}
