package dev.tubescribe.net;

/** A user-facing YouTube extraction failure with actionable guidance. */
public class YouTubeException extends Exception {
    public YouTubeException(final String message) {
        super(message);
    }

    public YouTubeException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
