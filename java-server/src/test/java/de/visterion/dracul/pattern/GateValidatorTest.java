package de.visterion.dracul.pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GateValidatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    // ---- valid gates, every field/op combination ----

    @Test
    void validSingleStringEq() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"mechanism","op":"eq","value":"INSIDER_CLUSTER"}]}"""));
        assertThat(r.valid()).isTrue();
        assertThat(r.errors()).isEmpty();
        assertThat(r.predicate().conditions()).hasSize(1);
        var c = r.predicate().conditions().get(0);
        assertThat(c.field()).isEqualTo("mechanism");
        assertThat(c.op()).isEqualTo("eq");
        assertThat(c.stringValues()).containsExactly("INSIDER_CLUSTER");
        assertThat(c.numberValue()).isNull();
    }

    @Test
    void validEveryStringFieldAndOp() {
        var r = GateValidator.validate(json("""
                {"conditions":[
                  {"field":"mechanism","op":"ne","value":"PEAD"},
                  {"field":"symbol","op":"in","value":["ACME","FOO"]},
                  {"field":"sector","op":"not_in","value":["Biotechnology"]}
                ]}"""));
        assertThat(r.valid()).isTrue();
        assertThat(r.predicate().conditions()).hasSize(3);
        assertThat(r.predicate().conditions().get(1).stringValues()).containsExactly("ACME", "FOO");
    }

    @Test
    void validEveryNumericFieldAndOp() {
        var r = GateValidator.validate(json("""
                {"conditions":[
                  {"field":"price","op":"lt","value":5},
                  {"field":"price","op":"lte","value":5.5},
                  {"field":"confidence","op":"gt","value":0.6},
                  {"field":"confidence","op":"gte","value":0.7}
                ]}"""));
        assertThat(r.valid()).isTrue();
        assertThat(r.predicate().conditions().get(0).numberValue())
                .isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(r.predicate().conditions().get(0).stringValues()).isNull();
    }

    @Test
    void duplicateFieldsAllowedAsRange() {
        var r = GateValidator.validate(json("""
                {"conditions":[
                  {"field":"price","op":"gte","value":1},
                  {"field":"price","op":"lt","value":5}
                ]}"""));
        assertThat(r.valid()).isTrue();
    }

    // ---- invalid gates ----

    @Test
    void unknownFieldRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"direction","op":"eq","value":"BUY"}]}"""));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("unknown field"));
        assertThat(r.predicate()).isNull();
    }

    @Test
    void unknownOpRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"mechanism","op":"matches","value":"X"}]}"""));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("unknown op"));
    }

    @Test
    void numericOpOnStringFieldRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"mechanism","op":"lt","value":5}]}"""));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("op 'lt' not applicable"));
    }

    @Test
    void stringOpOnNumericFieldRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"price","op":"eq","value":5}]}"""));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("op 'eq' not applicable"));
    }

    @Test
    void emptyConditionsRejected() {
        var r = GateValidator.validate(json("{\"conditions\":[]}"));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("non-empty"));
    }

    @Test
    void nineConditionsRejected() {
        StringBuilder sb = new StringBuilder("{\"conditions\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"field\":\"price\",\"op\":\"lt\",\"value\":").append(i + 1).append("}");
        }
        sb.append("]}");
        var r = GateValidator.validate(json(sb.toString()));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("max 8"));
    }

    @Test
    void inWithEmptyArrayRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"symbol","op":"in","value":[]}]}"""));
        assertThat(r.valid()).isFalse();
    }

    @Test
    void inWithNonStringElementRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"symbol","op":"in","value":["ACME",7]}]}"""));
        assertThat(r.valid()).isFalse();
    }

    @Test
    void numericValueAsStringRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"price","op":"lt","value":"5"}]}"""));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("must be a number"));
    }

    @Test
    void stringValueAsNumberRejected() {
        var r = GateValidator.validate(json("""
                {"conditions":[{"field":"mechanism","op":"eq","value":7}]}"""));
        assertThat(r.valid()).isFalse();
    }

    @Test
    void missingConditionsKeyRejected() {
        var r = GateValidator.validate(json("{\"foo\":1}"));
        assertThat(r.valid()).isFalse();
    }

    @Test
    void nonObjectRootRejected() {
        var r = GateValidator.validate(json("[1,2]"));
        assertThat(r.valid()).isFalse();
    }

    // ---- String overload ----

    @Test
    void stringOverloadParsesValidJson() {
        var r = GateValidator.validate(
                "{\"conditions\":[{\"field\":\"sector\",\"op\":\"eq\",\"value\":\"Biotechnology\"}]}");
        assertThat(r.valid()).isTrue();
    }

    @Test
    void stringOverloadRejectsUnparseableJson() {
        var r = GateValidator.validate("{not json");
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("invalid JSON"));
    }

    @Test
    void stringOverloadRejectsNull() {
        var r = GateValidator.validate((String) null);
        assertThat(r.valid()).isFalse();
    }
}
