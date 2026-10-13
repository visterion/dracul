package de.visterion.dracul.benchmark;

import java.math.BigDecimal;
import java.util.List;

/** Paper depot vs EUNL: TWR over the DAILY equity curve (from/to = its first/last day) plus the
 *  per-source-Strigoi breakdown. Fractions. */
public record DepotBenchmark(String connection, String from, String to, BigDecimal twr,
                             BigDecimal benchmarkReturn, BigDecimal excess, List<StrigoiDepotBenchmark> byStrigoi) {
}
