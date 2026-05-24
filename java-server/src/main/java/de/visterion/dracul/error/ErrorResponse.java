package de.visterion.dracul.error;

public record ErrorResponse(String error, String message, String field) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null);
    }
}
