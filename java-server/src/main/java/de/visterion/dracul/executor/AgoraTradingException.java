package de.visterion.dracul.executor;

/** Raised when an Agora trading tool reports unavailable or an HTTP error occurs. */
public class AgoraTradingException extends RuntimeException {
    public AgoraTradingException(String message) { super(message); }
    public AgoraTradingException(String message, Throwable cause) { super(message, cause); }
}
