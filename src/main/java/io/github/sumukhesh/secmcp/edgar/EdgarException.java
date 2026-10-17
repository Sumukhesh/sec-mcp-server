package io.github.sumukhesh.secmcp.edgar;

/**
 * Failure talking to SEC EDGAR. Messages are written to be safe and useful to show an MCP client.
 */
public class EdgarException extends RuntimeException {

    public EdgarException(String message) {
        super(message);
    }

    public EdgarException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The SEC returned 404: the CIK, accession number or document does not exist. */
    public static class NotFound extends EdgarException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The SEC kept failing (429/5xx/network) after all retries. */
    public static class Unavailable extends EdgarException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
