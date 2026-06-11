package de.visterion.dracul.settings;

import java.util.List;

public interface DataSourceHealthService {
    /** Probe all sources. Returns a cached result (≤60s old) unless refresh is true. */
    List<DataSourceHealth> probeAll(boolean refresh);
}
