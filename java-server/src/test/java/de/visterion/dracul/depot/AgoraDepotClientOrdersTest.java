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
            o.path("submittedAt").asText(null), o.path("filledAt").asText(null), null, null, null);
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

    @Test
    void entryOrderCarriesLimitPriceButNotStopPrice() throws Exception {
        JsonNode o = mapper.readTree("""
          {"brokerOrderId":"5039279121","symbol":"STT","side":"buy","qty":6.0,"type":"limit",
           "status":"working","role":"entry","limitPrice":182.53}
        """);
        DepotOrder order = toDepotOrder(o);
        assertThat(order.limitPrice()).isEqualByComparingTo(new BigDecimal("182.53"));
        assertThat(order.stopPrice()).isNull();
    }

    @Test
    void stopLossOrderCarriesStopPriceButNotLimitPrice() throws Exception {
        JsonNode o = mapper.readTree("""
          {"brokerOrderId":"5039279123","symbol":"STT","type":"stopiftraded","status":"notworking",
           "role":"stop_loss","stopPrice":168.03,"parentId":"5039279121"}
        """);
        DepotOrder order = toDepotOrder(o);
        assertThat(order.stopPrice()).isEqualByComparingTo(new BigDecimal("168.03"));
        assertThat(order.limitPrice()).isNull();
    }

    @Test
    void limitAndStopPriceAbsentInJsonMapToNull() throws Exception {
        JsonNode o = mapper.readTree("""
          {"brokerOrderId":"1","symbol":"STT","type":"market","status":"filled","role":"entry"}
        """);
        DepotOrder order = toDepotOrder(o);
        assertThat(order.limitPrice()).isNull();
        assertThat(order.stopPrice()).isNull();
    }

    private DepotOrder toDepotOrder(JsonNode o) {
        return new DepotOrder(
            o.path("brokerOrderId").asText(null), o.path("symbol").asText(null),
            o.path("side").asText(null),
            o.hasNonNull("qty") ? new BigDecimal(o.path("qty").asText("0")) : null,
            o.path("type").asText(null), o.path("status").asText(null),
            o.path("role").asText(null), o.path("parentId").asText(null),
            o.path("submittedAt").asText(null), o.path("filledAt").asText(null),
            o.hasNonNull("avgFillPrice") ? new BigDecimal(o.path("avgFillPrice").asText()) : null,
            o.hasNonNull("limitPrice") ? new BigDecimal(o.path("limitPrice").asText()) : null,
            o.hasNonNull("stopPrice") ? new BigDecimal(o.path("stopPrice").asText()) : null);
    }
}
