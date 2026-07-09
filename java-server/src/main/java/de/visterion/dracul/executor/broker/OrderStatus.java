package de.visterion.dracul.executor.broker;

/** Lifecycle state of a broker order. */
public enum OrderStatus {
    WORKING, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED
}
