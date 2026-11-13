package dev.tubescribe.media;

import java.io.IOException;

/** Where a streamed DASH track gets the bytes that are not in its cache yet. */
public interface StreamUrl {
    /** The URL to fetch from. May block while it is being resolved. */
    String get() throws IOException;

    /** A fresh URL after the server refused the current one, or null if it cannot be refreshed. */
    default String refresh() throws IOException {
        return null;
    }

    static StreamUrl fixed(final String url) {
        return () -> url;
    }
}
