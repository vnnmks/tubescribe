package dev.tubescribe.net;

import java.util.regex.Pattern;

/**
 * Resolved YouTube media for one video. Exactly one of {@link #muxedUrl()}
 * (single progressive MP4) or both {@link #videoUrl()} and {@link #audioUrl()}
 * (separate DASH streams) is set.
 */
public record VideoInfo(
        String id,
        String title,
        String uploader,
        long durationSeconds,
        String canonicalUrl,
        String muxedUrl,
        String videoUrl,
        String audioUrl,
        int height,
        int fps,
        String qualityLabel) {

    private static final Pattern RESOLUTION = Pattern.compile("(\\d{3,4})");

    public boolean requiresMerge() {
        return muxedUrl == null && videoUrl != null && audioUrl != null;
    }

    public String planLabel() {
        if (muxedUrl != null) {
            return "progressive " + qualityLabel;
        }
        return "DASH " + qualityLabel;
    }

    public static int parseHeight(final String resolution) {
        if (resolution == null) {
            return 0;
        }
        final var matcher = RESOLUTION.matcher(resolution);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
