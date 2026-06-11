package de.visterion.dracul.settings;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/** Pure mapping from an HTTP outcome to a data-source status string. */
public final class DataSourceStatus {

    private DataSourceStatus() {}

    public static String classify(Integer httpStatus, Throwable error) {
        if (error != null) {
            return isTimeout(error) ? "timeout" : "error";
        }
        if (httpStatus == null) return "error";
        if (httpStatus == 429) return "rate_limited";
        if (httpStatus >= 200 && httpStatus < 300) return "ok";
        return "error";
    }

    // A read/connect timeout from the JDK HTTP client surfaces as SocketTimeoutException
    // (extends InterruptedIOException); CompletableFuture-level timeouts surface as TimeoutException.
    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof InterruptedIOException) return true;
        }
        return false;
    }
}
