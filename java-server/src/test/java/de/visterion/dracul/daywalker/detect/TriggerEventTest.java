package de.visterion.dracul.daywalker.detect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerEventTest {

    @Test
    void toEventPayloadUsesSnakeCaseKeys() {
        var ev = new TriggerEvent("ACME", "Acme Corp", TriggerType.PRICE_SPIKE,
                new BigDecimal("104.0"), Map.of("price_change_pct", 0.05));

        Map<String, Object> payload = ev.toEventPayload();

        assertThat(payload.get("symbol")).isEqualTo("ACME");
        assertThat(payload.get("company_name")).isEqualTo("Acme Corp");
        assertThat(payload.get("trigger_type")).isEqualTo("PRICE_SPIKE");
        assertThat(payload.get("current_price")).isEqualTo(new BigDecimal("104.0"));
        assertThat(payload).containsKey("detail");
    }
}
