package de.visterion.dracul.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Best-effort Telegram Bot API notifier for critical Daywalker alerts. Graceful
 * degradation: a blank bot-token or chat-id disables push (no HTTP); any error
 * is logged and returns false. Never throws — push must never affect alert
 * persistence.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final RestClient http;
    private final String botToken;
    private final String chatId;

    @Autowired
    public TelegramNotifier(
            @Value("${dracul.telegram.bot-token:}") String botToken,
            @Value("${dracul.telegram.chat-id:}") String chatId,
            @Value("${dracul.telegram.base-url:https://api.telegram.org}") String baseUrl) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor: pre-built RestClient + explicit token/chat.
    TelegramNotifier(RestClient http, String botToken, String chatId) {
        this.http = http;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    /** Returns true only on a successful send; false if disabled or on any error. */
    public boolean notifyAlert(String symbol, String triggerType, String severity, String thesis) {
        // Plain text — NO parse_mode. trigger types contain underscores (PRICE_SPIKE,
        // INSIDER_SELL, …) which Telegram's Markdown parser treats as unbalanced italic
        // entities and rejects with HTTP 400. Plain text is robust against any dynamic
        // content (symbol / trigger / thesis) without escaping.
        String text = String.format("🔴 %s — %s (%s)%n%s",
                severity, symbol, triggerType, thesis == null ? "" : thesis);
        return send(text);
    }

    /** Sends a pre-rendered digest (the morning report). Plain text — no parse_mode. */
    public boolean notifyDigest(String text) {
        return send(text);
    }

    /** Telegram rejects a sendMessage body over 4096 characters with HTTP 400. */
    private static final int TELEGRAM_LIMIT = 4096;
    /** Room for the "[i/n]\n" marker; 12 covers "[99/99]\n" with slack. */
    private static final int MARKER_RESERVE = 12;
    /**
     * Payload per part. Deliberately 4000 rather than {@link #TELEGRAM_LIMIT}: Java's
     * {@code length()} counts UTF-16 units and so does Telegram, but the margin costs
     * nothing and a miscount here reproduces exactly the silent loss this code exists to
     * prevent — on 2026-08-17/18/19 every Renfield digest (4053-6657 chars) was dropped.
     */
    static final int CHUNK_BUDGET = 4000 - MARKER_RESERVE;

    /**
     * Splits at line boundaries into parts of at most {@code budget} characters.
     *
     * <p>Each part keeps the trailing {@code \n} of its last line, so
     * {@code String.join("", split(text, b)).equals(text)} holds exactly — for
     * line-boundary splits and for hard-split over-long lines alike. Do not join the
     * parts with {@code \n}: that would duplicate a newline per part.
     *
     * <p>A single line longer than the budget is hard-split rather than dropped. A
     * truncated rationale is bad; a missing one is worse.
     */
    static List<String> split(String text, int budget) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? text.length() : nl + 1;   // the segment owns its newline
            String seg = text.substring(i, end);
            i = end;
            while (seg.length() > budget) {
                if (!cur.isEmpty()) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                parts.add(seg.substring(0, budget));
                seg = seg.substring(budget);
            }
            if (cur.length() + seg.length() > budget) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(seg);
        }
        if (!cur.isEmpty() || parts.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    private boolean send(String text) {
        if (botToken.isBlank() || chatId.isBlank()) return false;
        List<String> parts = split(text, CHUNK_BUDGET);
        int n = parts.size();
        if (n > 1) {
            log.info("telegram message split: parts={} chars={}", n, text.length());
        }
        for (int i = 0; i < n; i++) {
            String body = n == 1 ? parts.get(0) : "[" + (i + 1) + "/" + n + "]\n" + parts.get(i);
            String error = post(body);
            if (error != null) {
                if (n == 1) {
                    log.warn("Telegram push failed: {}", error);
                } else {
                    log.warn("telegram digest incomplete: sent {} of {} parts — {}", i, n, error);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * One sendMessage call. Returns null on success, the error text otherwise — the
     * caller decides how to phrase the failure, because a partial multi-part send needs
     * to say how far it got and a single-part send does not.
     */
    private String post(String text) {
        try {
            // Token is concatenated (not a URI variable) so its ':' is not percent-encoded.
            http.post()
                    .uri("/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
}
