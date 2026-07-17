package de.visterion.dracul.prey;

import de.visterion.dracul.voievod.Horizons;

import java.time.LocalDate;
import java.util.List;

/** Filters prey lists down to still-active (non-expired) entries for display. Read-only —
 *  never deletes or mutates prey; {@link de.visterion.dracul.voievod.Horizons#isOpen} decides
 *  what "active" means, including its fail-open behavior for unparseable dates/horizons. */
public final class PreyLifecycle {

    private PreyLifecycle() {}

    public static List<Prey> activeOnly(List<Prey> all, LocalDate today) {
        return all.stream()
                .filter(p -> Horizons.isOpen(p.discoveredAt(), p.horizon(), today))
                .toList();
    }
}
