package de.visterion.dracul.hunting.edgar;

public class EdgarException extends RuntimeException {
    public EdgarException(String reason, Throwable cause) { super(reason, cause); }
    public EdgarException(String reason) { super(reason); }
}
