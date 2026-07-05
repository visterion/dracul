package de.visterion.dracul.hunting.agora;

public record RecommendationTrend(String period, int strongBuy, int buy,
                                  int hold, int sell, int strongSell) {}
