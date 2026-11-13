package dev.tubescribe.media;

import dev.tubescribe.net.VideoInfo;
import dev.tubescribe.net.YouTubeClient;

import java.io.IOException;

/**
 * The stream link of a partly cached DASH video, resolved the first time the
 * player needs bytes that are not on disk. Opening the player on the cached
 * prefix therefore costs no network round trip: the page fetch happens on the
 * loader thread, only when playback reaches uncached data or seeks past it.
 *
 * <p>The cached bytes belong to one concrete stream, so a resolve that lands
 * on a different layout or height is refused instead of splicing two streams
 * into one corrupt file.</p>
 */
public final class LazyStreamUrl implements StreamUrl {
    private final String pageUrl;
    private final int expectedHeight;
    private String url;

    public LazyStreamUrl(final String pageUrl, final int expectedHeight) {
        this.pageUrl = pageUrl;
        this.expectedHeight = expectedHeight;
    }

    @Override
    public synchronized String get() throws IOException {
        if (url == null) {
            url = resolve();
        }
        return url;
    }

    @Override
    public synchronized String refresh() throws IOException {
        url = resolve();
        return url;
    }

    private String resolve() throws IOException {
        final VideoInfo info;
        try {
            YouTubeClient.initialize(null);
            info = new YouTubeClient().resolve(pageUrl,
                    Quality.fromHeight(expectedHeight, Quality.P720));
        } catch (final Exception e) {
            throw new IOException("Could not reach YouTube for the rest of the video: "
                    + e.getMessage(), e);
        }
        final String videoUrl = info.videoUrl();
        if (!info.requiresMerge() || videoUrl == null || videoUrl.isBlank()
                || (expectedHeight > 0 && info.height() != expectedHeight)) {
            throw new IOException("YouTube no longer offers the stream this video was cached "
                    + "from. Fetch it again from its link to refresh the cache.");
        }
        return videoUrl;
    }
}
