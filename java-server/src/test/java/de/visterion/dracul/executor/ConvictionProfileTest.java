package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConvictionProfileTest {

    private final ConvictionProfile profile = ConvictionProfile.defaults();

    @Test
    void defaultsAreTheSpecValues() {
        assertThat(profile.emergencyStopPct()).isEqualByComparingTo("0.35");
        assertThat(profile.targetPct()).isEqualByComparingTo("0.30");
        assertThat(profile.targetFraction()).isEqualByComparingTo("0.5");
        assertThat(profile.trailPct()).isEqualByComparingTo("0.30");
        assertThat(profile.minEntryQty()).isEqualTo(2);
        assertThat(profile.entryBrokerStopPct()).isEqualByComparingTo("0.20");
        assertThat(profile.positionPct()).isEqualByComparingTo("0.03");
    }

    @Test
    void emergencyStopIsThirtyFivePercentBelowEntryRoundedTowardTheEntry() {
        assertThat(profile.emergencyStop("BUY", new BigDecimal("100.00"))).isEqualByComparingTo("65.00");
        // 57.30 x 0.65 = 37.245 -> BUY stops round UP (toward the entry): 37.25
        assertThat(profile.emergencyStop("BUY", new BigDecimal("57.30"))).isEqualByComparingTo("37.25");
        assertThat(profile.emergencyStop("SELL", new BigDecimal("100.00"))).isEqualByComparingTo("135.00");
    }

    @Test
    void entryBrokerLegSitsAtTheBandButNeverBeyondTheLogicalStop() {
        assertThat(profile.entryBrokerStop("BUY", new BigDecimal("100.00"), new BigDecimal("65.00")))
                .isEqualByComparingTo("80.00");
        assertThat(profile.entryBrokerStop("BUY", new BigDecimal("57.30"), new BigDecimal("37.25")))
                .isEqualByComparingTo("45.84");
        // A band WIDER than the emergency stop must not put the leg below the logical stop.
        ConvictionProfile wideBand = new ConvictionProfile(new BigDecimal("0.10"),
                new BigDecimal("0.30"), new BigDecimal("0.5"), new BigDecimal("0.30"), 2,
                new BigDecimal("0.20"), new BigDecimal("0.033"), new BigDecimal("0.025"), 1);
        assertThat(wideBand.entryBrokerStop("BUY", new BigDecimal("100.00"), new BigDecimal("90.00")))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void targetAndTrailLevels() {
        assertThat(profile.targetPrice(new BigDecimal("100.00"))).isEqualByComparingTo("130.00");
        assertThat(profile.trailStop("BUY", new BigDecimal("140.00"))).isEqualByComparingTo("98.00");
        // 141.37 x 0.70 = 98.959 -> FLOOR (away from the market, never a premature stop-out)
        assertThat(profile.trailStop("BUY", new BigDecimal("141.37"))).isEqualByComparingTo("98.95");
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new ConvictionProfile(new BigDecimal("1.2"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 2, new BigDecimal("0.20"),
                new BigDecimal("0.033"), new BigDecimal("0.025"), 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emergency-stop-pct");
        assertThatThrownBy(() -> new ConvictionProfile(new BigDecimal("0.35"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 0, new BigDecimal("0.20"),
                new BigDecimal("0.033"), new BigDecimal("0.025"), 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-entry-qty");
    }

    /** Spec 2026-10-04 §3: notional and min-entry-qty per wide-stop profile. */
    @Test
    void pctAndMinEntryQtyPerProfile() {
        ConvictionProfile d = ConvictionProfile.defaults();
        assertThat(d.pctFor(ExitProfile.CONVICTION)).isEqualByComparingTo("0.03");
        assertThat(d.pctFor(ExitProfile.MOMENTUM)).isEqualByComparingTo("0.025");
        assertThat(d.pctFor(ExitProfile.STANDARD)).isEqualByComparingTo("0.03");   // never read for STANDARD
        assertThat(d.minEntryQtyFor(ExitProfile.CONVICTION)).isEqualTo(2);
        assertThat(d.minEntryQtyFor(ExitProfile.MOMENTUM)).isEqualTo(1);
    }

    @Test
    void rejectsNonsenseMomentumConfiguration() {
        assertThatThrownBy(() -> new ConvictionProfile(new BigDecimal("0.35"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 2, new BigDecimal("0.20"),
                new BigDecimal("0.03"), new BigDecimal("1.5"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dracul.strigoi.momentum.position-pct");
        assertThatThrownBy(() -> new ConvictionProfile(new BigDecimal("0.35"), new BigDecimal("0.30"),
                new BigDecimal("0.5"), new BigDecimal("0.30"), 2, new BigDecimal("0.20"),
                new BigDecimal("0.03"), new BigDecimal("0.025"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dracul.strigoi.momentum.min-entry-qty");
    }
}
