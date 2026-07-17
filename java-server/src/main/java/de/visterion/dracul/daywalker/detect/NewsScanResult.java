package de.visterion.dracul.daywalker.detect;

import java.util.List;
import java.util.Optional;

public record NewsScanResult(Optional<TriggerEvent> trigger, List<MacroHeadline> macroOnly) {
    public static NewsScanResult empty() {
        return new NewsScanResult(Optional.empty(), List.of());
    }
}
