package de.visterion.dracul.status;

import de.visterion.dracul.vistierie.StrigoiStatus;
import java.util.List;

public record SystemStatus(
        List<StrigoiStatus> strigoi,
        String lastVerdictAt,
        double dailyCostUsd,
        boolean daywalkerActive) {}
