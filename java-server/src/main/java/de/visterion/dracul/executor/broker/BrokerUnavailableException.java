package de.visterion.dracul.executor.broker;

/** Raised when the broker connection is unreachable or reports itself unavailable. */
public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(String message) { super(message); }
    public BrokerUnavailableException(String message, Throwable cause) { super(message, cause); }
}
