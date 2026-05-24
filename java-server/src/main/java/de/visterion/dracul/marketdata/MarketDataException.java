package de.visterion.dracul.marketdata;

public class MarketDataException extends RuntimeException {
    public enum Kind { NOT_FOUND, UNAVAILABLE }
    private final Kind kind;

    public MarketDataException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
    public MarketDataException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
