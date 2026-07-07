package de.visterion.dracul.daywalker.detect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerEventTest {

    @Test
    void toEventPayloadUsesSnakeCaseKeys() {
        var ev = TriggerEvent.watchOnly("ACME", "Acme Corp", TriggerType.PRICE_SPIKE,
                new BigDecimal("104.0"), Map.of("price_change_pct", 0.05));

        Map<String, Object> payload = ev.toEventPayload();

        assertThat(payload.get("symbol")).isEqualTo("ACME");
        assertThat(payload.get("company_name")).isEqualTo("Acme Corp");
        assertThat(payload.get("trigger_type")).isEqualTo("PRICE_SPIKE");
        assertThat(payload.get("current_price")).isEqualTo(new BigDecimal("104.0"));
        assertThat(payload).containsKey("detail");
    }

    @Test void payloadIncludesPositionContextWhenHeld() {
        var pos = new PositionContext(
                new BigDecimal("100"), new BigDecimal("12.5"), new BigDecimal("92"),
                new BigDecimal("120"), new BigDecimal("2"), new BigDecimal("4"));
        var ev = new TriggerEvent("ACME", "ACME Corp", TriggerType.PRICE_SPIKE,
                new BigDecimal("95"), Map.of("price_change_pct", 0.05),
                "wid-1", pos, "STOP");
        var payload = ev.toEventPayload();
        assertThat(payload).containsEntry("position_id", "wid-1");
        assertThat(payload).containsEntry("breached_level", "STOP");
        assertThat(payload).containsKey("position");
    }

    @Test void payloadOmitsPositionContextWhenWatchOnly() {
        var ev = TriggerEvent.watchOnly("ACME", "ACME Corp", TriggerType.PRICE_SPIKE,
                new BigDecimal("95"), Map.of());
        var payload = ev.toEventPayload();
        assertThat(payload).doesNotContainKey("position_id");
        assertThat(payload).doesNotContainKey("position");
        assertThat(payload).doesNotContainKey("breached_level");
    }
}
