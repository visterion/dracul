package de.visterion.dracul.hivemem;

/** Raised when HiveMem is unreachable, returns an error, or the response can't be parsed. */
public class HiveMemUnavailableException extends RuntimeException {
  public HiveMemUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public HiveMemUnavailableException(String message) {
    super(message, null);
  }
}
