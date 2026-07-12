package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.agora.Form4Filing;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class InsiderClusterScreener {

    private static final int MIN_FILERS = 3;
    private static final long WINDOW_DAYS = 30;
    private static final BigDecimal MIN_DOLLAR = new BigDecimal("500000");

    public List<InsiderCluster> cluster(List<Form4Filing> filings) {
        Map<String, List<Form4Filing>> byTicker = filings.stream()
                .collect(Collectors.groupingBy(Form4Filing::ticker));

        List<InsiderCluster> result = new ArrayList<>();
        for (var entry : byTicker.entrySet()) {
            var sorted = entry.getValue().stream()
                    .filter(f -> "P".equals(f.transactionCode()))
                    .sorted(Comparator.comparing(Form4Filing::transactionDate))
                    .toList();
            for (int rightIdx = sorted.size() - 1; rightIdx >= 0; rightIdx--) {
                LocalDate right = sorted.get(rightIdx).transactionDate();
                int leftIdx = 0;
                for (int i = rightIdx; i >= 0; i--) {
                    if (ChronoUnit.DAYS.between(sorted.get(i).transactionDate(), right) > WINDOW_DAYS) {
                        leftIdx = i + 1;
                        break;
                    }
                    leftIdx = i;
                }
                var window = sorted.subList(leftIdx, rightIdx + 1);
                LinkedHashMap<String, String> roleByFiler = new LinkedHashMap<>();
                for (Form4Filing f : window) {
                    roleByFiler.merge(f.filerName(),
                            f.filerRole() == null ? "" : f.filerRole().trim(),
                            (existing, incoming) -> existing.isBlank() ? incoming : existing);
                }
                List<InsiderFiler> filers = roleByFiler.entrySet().stream()
                        .map(e -> InsiderFiler.unclassified(e.getKey(), e.getValue()))
                        .toList();
                BigDecimal totalDollar = window.stream()
                        .map(Form4Filing::dollarValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalShares = window.stream()
                        .map(Form4Filing::sharesAcquired)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (filers.size() >= MIN_FILERS && totalDollar.compareTo(MIN_DOLLAR) > 0) {
                    LocalDate windowStart = window.get(0).transactionDate();
                    List<Form4Filing> concurrentSells = entry.getValue().stream()
                            .filter(f -> "S".equals(f.transactionCode()))
                            .filter(f -> !f.transactionDate().isBefore(windowStart)
                                    && !f.transactionDate().isAfter(right))
                            .toList();
                    int distinctSellers = (int) concurrentSells.stream()
                            .map(Form4Filing::filerName).distinct().count();
                    BigDecimal sellDollar = concurrentSells.stream()
                            .map(Form4Filing::dollarValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    result.add(new InsiderCluster(
                            entry.getKey(), entry.getKey(),
                            filers,
                            windowStart,
                            right,
                            totalDollar,
                            totalShares,
                            distinctSellers,
                            totalDollar.subtract(sellDollar)
                    ));
                    break;
                }
            }
        }
        return result;
    }
}
