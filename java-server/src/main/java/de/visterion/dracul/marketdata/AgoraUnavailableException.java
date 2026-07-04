package de.visterion.dracul.marketdata;

/** Raised when Agora is unreachable, returns an error, or the response can't be parsed. */
public class AgoraUnavailableException extends RuntimeException {
    public AgoraUnavailableException(String message, Throwable cause) { super(message, cause); }
    public AgoraUnavailableException(String message) { super(message, null); }
}
