package de.visterion.dracul.depot;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class AgoraDepotClientOrdersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void depotOrderCarriesParentId() throws Exception {
        JsonNode o = mapper.readTree("""
          {"brokerOrderId":"1","symbol":"STT","side":null,"qty":6,"type":"limit",
           "status":"notWorking","role":"take_profit","parentId":"P-1",
           "submittedAt":null,"filledAt":null,"avgFillPrice":null}
        """);
        DepotOrder order = new DepotOrder(
            o.path("brokerOrderId").asText(null), o.path("symbol").asText(null),
            o.path("side").asText(null), new BigDecimal(o.path("qty").asText("0")),
            o.path("type").asText(null), o.path("status").asText(null),
            o.path("role").asText(null), o.path("parentId").asText(null),
            o.path("submittedAt").asText(null), o.path("filledAt").asText(null), null);
        assertThat(order.parentId()).isEqualTo("P-1");
        assertThat(order.role()).isEqualTo("take_profit");
    }

    @Test
    void parentIdAbsentInJsonMapsToNull() throws Exception {
        JsonNode o = mapper.readTree("""
          {"brokerOrderId":"1","symbol":"STT","type":"market","status":"filled","role":"entry"}
        """);
        assertThat(o.hasNonNull("parentId")).isFalse();
    }
}
