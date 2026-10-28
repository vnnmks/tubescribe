package dev.tubescribe.media;

import java.io.IOException;

/** A media or tool failure with a message suitable for the UI. */
public class MediaException extends IOException {
    public MediaException(final String message) {
        super(message);
    }

    public MediaException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
