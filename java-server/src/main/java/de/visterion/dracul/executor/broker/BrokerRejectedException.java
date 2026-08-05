package de.visterion.dracul.executor.broker;

import java.util.List;

/**
 * Raised when Agora explicitly rejected a write ({@code accepted:false}), as opposed to the
 * broker connection being unreachable. Subclasses {@link BrokerUnavailableException} so every
 * existing {@code catch (BrokerUnavailableException)} keeps working unchanged, while callers
 * that care can distinguish a business rejection — in particular
 * {@code LEG_RESTORE_FAILED_UNPROTECTED}, which means a position may now sit at the broker
 * with less protection than it holds and must not be treated like a transient rate limit.
 */
public class BrokerRejectedException extends BrokerUnavailableException {

    private final String rejectCode;
    private final List<RestoredLeg> protectiveLegs;

    public BrokerRejectedException(String message, String rejectCode, List<RestoredLeg> protectiveLegs) {
        super(message);
        this.rejectCode = rejectCode;
        this.protectiveLegs = protectiveLegs;
    }

    /** The reject code Agora reported, e.g. {@code LEG_RESTORE_FAILED_UNPROTECTED}. May be null. */
    public String rejectCode() {
        return rejectCode;
    }

    /** Protective legs Agora rolled back and re-issued as part of the rejection. Never null. */
    public List<RestoredLeg> protectiveLegs() {
        return protectiveLegs;
    }
}
