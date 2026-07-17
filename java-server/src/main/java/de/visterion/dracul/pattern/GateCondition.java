package de.visterion.dracul.pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * One validated gate condition. For string ops ({@code eq}/{@code ne}/{@code in}/{@code not_in})
 * {@code stringValues} is set ({@code eq}/{@code ne} carry exactly one element) and
 * {@code numberValue} is null; for numeric ops ({@code lt}/{@code lte}/{@code gt}/{@code gte})
 * it is the other way around.
 */
public record GateCondition(String field, String op, List<String> stringValues,
        BigDecimal numberValue) {}
