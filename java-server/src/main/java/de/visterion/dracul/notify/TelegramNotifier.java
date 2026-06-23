package de.visterion.dracul.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private boolean send(String text) {
        if (botToken.isBlank() || chatId.isBlank()) return false;
        try {
            // Token is concatenated (not a URI variable) so its ':' is not percent-encoded.
            http.post()
                    .uri("/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Telegram push failed: {}", e.getMessage());
            return false;
        }
    }
}
