package de.visterion.dracul.depot;

/** Thrown when Agora's depot read tools return {@code available:false} or the HTTP call fails. */
public class DepotUnavailableException extends RuntimeException {

    public DepotUnavailableException(String message) {
        super(message);
    }

    public DepotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
