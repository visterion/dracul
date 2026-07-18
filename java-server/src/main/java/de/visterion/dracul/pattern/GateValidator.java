package de.visterion.dracul.pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single shared gate validator (spec T3.3 D1): JSON → {@link GatePredicate} or an error
 * list. Used by the voievod-outcome completion handler, the {@code update_gate} PATCH action,
 * and defensively at evaluation time (VetoService / PatternOutcomeScorer). Pure and static —
 * no Spring wiring needed.
 */
public final class GateValidator {

    public record Result(GatePredicate predicate, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Set<String> STRING_FIELDS = Set.of("mechanism", "symbol", "sector");
    private static final Set<String> NUMERIC_FIELDS = Set.of("confidence", "price");
    private static final Set<String> STRING_OPS = Set.of("eq", "ne", "in", "not_in");
    private static final Set<String> NUMERIC_OPS = Set.of("lt", "lte", "gt", "gte");
    private static final int MAX_CONDITIONS = 8;

    private GateValidator() {}

    /** Parses and validates a raw JSON string (e.g. the {@code gate} column). */
    public static Result validate(String gateJson) {
        if (gateJson == null || gateJson.isBlank()) {
            return new Result(null, List.of("invalid JSON: null or blank"));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(gateJson);
        } catch (RuntimeException e) {
            return new Result(null, List.of("invalid JSON: " + e.getMessage()));
        }
        return validate(node);
    }

    public static Result validate(JsonNode gate) {
        List<String> errors = new ArrayList<>();
        if (gate == null || !gate.isObject()) {
            return new Result(null, List.of("gate must be a JSON object"));
        }
        JsonNode conditions = gate.path("conditions");
        if (!conditions.isArray() || conditions.isEmpty()) {
            return new Result(null, List.of("conditions must be a non-empty array"));
        }
        if (conditions.size() > MAX_CONDITIONS) {
            return new Result(null, List.of("conditions: max " + MAX_CONDITIONS + " allowed, got "
                    + conditions.size()));
        }
        List<GateCondition> parsed = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            GateCondition c = parseCondition(conditions.get(i), "conditions[" + i + "]", errors);
            if (c != null) parsed.add(c);
        }
        if (!errors.isEmpty()) return new Result(null, List.copyOf(errors));
        return new Result(new GatePredicate(List.copyOf(parsed)), List.of());
    }

    private static GateCondition parseCondition(JsonNode node, String at, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(at + ": condition must be an object");
            return null;
        }
        String field = node.path("field").isTextual() ? node.path("field").asString() : null;
        String op = node.path("op").isTextual() ? node.path("op").asString() : null;
        JsonNode value = node.path("value");

        boolean stringField = field != null && STRING_FIELDS.contains(field);
        boolean numericField = field != null && NUMERIC_FIELDS.contains(field);
        if (!stringField && !numericField) {
            errors.add(at + ": unknown field '" + field + "'");
            return null;
        }
        boolean stringOp = op != null && STRING_OPS.contains(op);
        boolean numericOp = op != null && NUMERIC_OPS.contains(op);
        if (!stringOp && !numericOp) {
            errors.add(at + ": unknown op '" + op + "'");
            return null;
        }
        if (stringField && !stringOp) {
            errors.add(at + ": op '" + op + "' not applicable to string field '" + field + "'");
            return null;
        }
        if (numericField && !numericOp) {
            errors.add(at + ": op '" + op + "' not applicable to numeric field '" + field + "'");
            return null;
        }

        if (numericOp) {
            if (!value.isNumber()) {
                errors.add(at + ": value for op '" + op + "' must be a number");
                return null;
            }
            return new GateCondition(field, op, null, value.decimalValue());
        }
        // string ops
        if ("in".equals(op) || "not_in".equals(op)) {
            if (!value.isArray() || value.isEmpty()) {
                errors.add(at + ": value for op '" + op + "' must be a non-empty string array");
                return null;
            }
            List<String> values = new ArrayList<>();
            for (JsonNode v : value) {
                if (!v.isTextual()) {
                    errors.add(at + ": value array for op '" + op + "' must contain only strings");
                    return null;
                }
                values.add(v.asString());
            }
            return new GateCondition(field, op, List.copyOf(values), null);
        }
        if (!value.isTextual()) {
            errors.add(at + ": value for op '" + op + "' must be a string");
            return null;
        }
        return new GateCondition(field, op, List.of(value.asString()), null);
    }
}
