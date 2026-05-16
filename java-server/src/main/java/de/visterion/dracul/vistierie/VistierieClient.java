package de.visterion.dracul.vistierie;

import de.visterion.dracul.strigoi.StrigoiDetail;
import java.util.List;
import java.util.Optional;

public interface VistierieClient {
    List<StrigoiStatus> listStrigoi();
    Optional<StrigoiDetail> getStrigoiDetail(String name);
    double getTodayCostUsd();
    List<LlmProvider> getProviders();
    List<VistierieData.DailySpend> getDashboardData();
}
