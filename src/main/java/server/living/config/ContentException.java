package server.living.config;

/** A content file is malformed or references something that does not exist. */
public final class ContentException extends RuntimeException {
    public ContentException(String message) {
        super(message);
    }

    public ContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
