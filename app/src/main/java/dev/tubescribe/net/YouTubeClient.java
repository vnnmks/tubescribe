package dev.tubescribe.net;

import okhttp3.CookieJar;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Thin, robust wrapper around NewPipeExtractor for a single YouTube video. */
public final class YouTubeClient {
    private static final Logger LOG = Logger.getLogger(YouTubeClient.class.getName());
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern YOUTUBE_HOST = Pattern.compile(
            "^(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtube-nocookie\\.com)$");

    private static volatile boolean configured;

    public YouTubeClient() {
    }

    /**
     * (Re)initializes NewPipe with the given cookies file. Passing {@code null}
     * uses an in-memory cookie jar with no cookies.
     *
     * @return number of YouTube/Google cookies loaded
     */
    public static synchronized int initialize(final Path cookiesFile) throws IOException {
        final Cookies.Loaded loaded = Cookies.load(cookiesFile);
        final CookieJar jar = new Cookies.MemoryJar(loaded.cookies());
        NewPipe.init(new NewPipeDownloader(jar));
        configured = true;
        if (loaded.youtubeCookies() == 0) {
            LOG.info("NewPipe initialized without YouTube cookies");
        } else {
            LOG.info("NewPipe initialized with " + loaded.youtubeCookies() + " YouTube/Google cookies");
        }
        return loaded.youtubeCookies();
    }

    private static synchronized void ensureConfigured() {
        if (!configured) {
            try {
                initialize(null);
            } catch (final IOException e) {
                throw new IllegalStateException("Cannot initialize NewPipe: " + e.getMessage(), e);
            }
        }
    }

    public static boolean looksLikeYouTubeUrl(final String input) {
        return extractVideoId(input) != null;
    }

    /** Extracts an 11-character video id from common YouTube URL shapes. */
    public static String extractVideoId(final String input) {
        if (input == null) {
            return null;
        }
        String value = input.strip();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        if (VIDEO_ID.matcher(value).matches()) {
            return value;
        }
        String withScheme = value;
        if (!withScheme.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            withScheme = "https://" + withScheme;
        }
        final URI uri;
        try {
            uri = URI.create(withScheme);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final String host = uri.getHost();
        if (host == null) {
            return null;
        }
        final String normalizedHost = host.toLowerCase(Locale.ROOT);
        final String path = uri.getPath() == null ? "" : uri.getPath();

        if (normalizedHost.equals("youtu.be") || normalizedHost.equals("www.youtu.be")) {
            return firstPathId(path);
        }
        if (!YOUTUBE_HOST.matcher(normalizedHost).matches()) {
            return null;
        }
        if (path.equals("/watch") || path.equals("/watch/")) {
            return queryParam(uri.getRawQuery(), "v");
        }
        for (final String prefix : List.of("/shorts/", "/live/", "/embed/", "/v/", "/e/")) {
            if (path.startsWith(prefix)) {
                final String candidate = firstPathId(path.substring(prefix.length() - 1));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        // Some links use /watch/<id>.
        if (path.startsWith("/watch/")) {
            return firstPathId(path.substring("/watch/".length() - 1));
        }
        return null;
    }

    public static String canonicalUrl(final String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public VideoInfo resolve(final String rawUrl, final dev.tubescribe.media.Quality quality)
            throws YouTubeException {
        final String id = extractVideoId(rawUrl);
        if (id == null) {
            throw new YouTubeException(
                    "That does not look like a YouTube video link. Paste a link such as "
                            + "https://www.youtube.com/watch?v=dQw4w9WgXcQ or https://youtu.be/dQw4w9WgXcQ.");
        }
        ensureConfigured();
        final String canonical = canonicalUrl(id);
        try {
            final StreamExtractor extractor = ServiceList.YouTube.getStreamExtractor(canonical);
            extractor.fetchPage();

            final String title = extractor.getName() == null ? id : extractor.getName();
            final String uploader = extractor.getUploaderName() == null ? "" : extractor.getUploaderName();
            final long duration = Math.max(0, extractor.getLength());

            final List<VideoStream> videoOnly = safe(extractor.getVideoOnlyStreams());
            final List<VideoStream> muxed = safe(extractor.getVideoStreams());
            final List<AudioStream> audio = safe(extractor.getAudioStreams());

            final VideoStream bestVideo = chooseVideo(videoOnly, quality.maxHeight());
            final VideoStream bestMuxed = chooseVideo(muxed, quality.maxHeight());
            final AudioStream bestAudio = chooseAudio(audio);

            // Prefer DASH video+audio, unless the progressive stream is taller:
            // some low-resolution videos only have small video-only H.264 streams
            // while their muxed stream is the best H.264/AAC combination.
            if (bestVideo != null && bestAudio != null
                    && (bestMuxed == null || heightOf(bestVideo) >= heightOf(bestMuxed))) {
                final String videoUrl = streamUrl(bestVideo);
                final String audioUrl = streamUrl(bestAudio);
                if (videoUrl != null && audioUrl != null) {
                    final int height = heightOf(bestVideo);
                    LOG.info("Selected DASH video itag=" + bestVideo.getItag() + " " + height + "p"
                            + " audio itag=" + bestAudio.getItag() + " "
                            + audioBitrate(bestAudio) + " bps for " + id);
                    return new VideoInfo(id, title, uploader, duration, canonical, null,
                            videoUrl, audioUrl, height, Math.max(0, bestVideo.getFps()),
                            qualityLabel(height, bestVideo.getFps()));
                }
            }

            if (bestMuxed != null) {
                final String muxedUrl = streamUrl(bestMuxed);
                if (muxedUrl != null) {
                    final int height = heightOf(bestMuxed);
                    LOG.info("Selected progressive itag=" + bestMuxed.getItag() + " " + height
                            + "p for " + id);
                    return new VideoInfo(id, title, uploader, duration, canonical, muxedUrl,
                            null, null, height, Math.max(0, bestMuxed.getFps()),
                            qualityLabel(height, bestMuxed.getFps()));
                }
            }

            throw new YouTubeException("No playable H.264/AAC MP4 stream was found for this video. "
                    + "Available video streams: " + describeVideoStreams(videoOnly, muxed)
                    + "; audio streams: " + describeAudioStreams(audio) + ".");
        } catch (final SignInConfirmNotBotException e) {
            final VideoInfo fallback = new InnertubeClient().resolve(id, quality);
            if (fallback != null) {
                LOG.info("Resolved " + id + " through the InnerTube VISIONOS fallback "
                        + "(the web extractor was bot-checked)");
                return fallback;
            }
            throw new YouTubeException(
                    "YouTube is asking this network to sign in before it will serve the video "
                            + "(“Sign in to confirm you're not a bot”), and the direct InnerTube "
                            + "fallback did not succeed either. Try again in a minute, or on a "
                            + "different network.", e);
        } catch (final ExtractionException e) {
            final VideoInfo fallback = new InnertubeClient().resolve(id, quality);
            if (fallback != null) {
                LOG.info("Resolved " + id + " through the InnerTube VISIONOS fallback "
                        + "(extractor error: " + e.getMessage() + ")");
                return fallback;
            }
            throw new YouTubeException("YouTube extraction failed: " + e.getMessage(), e);
        } catch (final IOException e) {
            final VideoInfo fallback = new InnertubeClient().resolve(id, quality);
            if (fallback != null) {
                LOG.info("Resolved " + id + " through the InnerTube VISIONOS fallback "
                        + "(network error: " + e.getMessage() + ")");
                return fallback;
            }
            throw new YouTubeException("Network error while contacting YouTube: " + e.getMessage(), e);
        }
    }

    private static VideoStream chooseVideo(final List<VideoStream> streams, final int maxHeight) {
        final List<VideoStream> candidates = new ArrayList<>();
        for (final VideoStream stream : streams) {
            if (stream.getFormat() != MediaFormat.MPEG_4 || !isAvc(stream.getCodec())) {
                continue;
            }
            final int height = heightOf(stream);
            if (height <= 0 || height > maxHeight) {
                continue;
            }
            if (streamUrl(stream) == null) {
                continue;
            }
            candidates.add(stream);
        }
        candidates.sort(Comparator
                .comparingInt(YouTubeClient::heightOf)
                .thenComparingInt(VideoStream::getFps)
                .thenComparingInt(VideoStream::getBitrate));
        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    private static AudioStream chooseAudio(final List<AudioStream> streams) {
        final List<AudioStream> candidates = new ArrayList<>();
        for (final AudioStream stream : streams) {
            if (stream.getFormat() != MediaFormat.M4A && stream.getFormat() != MediaFormat.MPEG_4) {
                continue;
            }
            if (!isAac(stream.getCodec())) {
                continue;
            }
            if (streamUrl(stream) == null) {
                continue;
            }
            candidates.add(stream);
        }
        candidates.sort(Comparator
                .comparingInt(YouTubeClient::audioTrackRank)
                .thenComparing(Comparator.comparingInt(YouTubeClient::audioBitrate).reversed()));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int audioTrackRank(final AudioStream stream) {
        return stream.getAudioTrackType() == AudioTrackType.ORIGINAL ? 0 : 1;
    }

    private static int audioBitrate(final AudioStream stream) {
        return stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate();
    }

    private static int heightOf(final VideoStream stream) {
        if (stream.getHeight() > 0) {
            return stream.getHeight();
        }
        return VideoInfo.parseHeight(stream.getResolution());
    }

    private static String qualityLabel(final int height, final int fps) {
        if (height <= 0) {
            return "unknown";
        }
        return height + "p" + (fps > 40 ? Integer.toString(fps) : "");
    }

    private static boolean isAvc(final String codec) {
        if (codec == null) {
            return false;
        }
        final String value = codec.toLowerCase(Locale.ROOT);
        return value.startsWith("avc") || value.contains("avc1")
                || value.contains("h264") || value.contains("h.264");
    }

    private static boolean isAac(final String codec) {
        if (codec == null) {
            return true;
        }
        final String value = codec.toLowerCase(Locale.ROOT);
        return value.contains("mp4a") || value.contains("aac");
    }

    private static String streamUrl(final org.schabi.newpipe.extractor.stream.Stream stream) {
        final String content = stream.isUrl() ? stream.getContent() : null;
        if (content != null && !content.isBlank()) {
            return content;
        }
        @SuppressWarnings("deprecation")
        final String deprecated = stream.getUrl();
        return deprecated == null || deprecated.isBlank() ? null : deprecated;
    }

    private static String firstPathId(final String path) {
        if (path == null) {
            return null;
        }
        String value = path;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        final int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        final int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return VIDEO_ID.matcher(value).matches() ? value : null;
    }

    private static String queryParam(final String query, final String name) {
        if (query == null) {
            return null;
        }
        for (final String part : query.split("&")) {
            final int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(name)) {
                final String value = part.substring(equals + 1);
                return VIDEO_ID.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }

    private static String describeVideoStreams(final List<VideoStream> videoOnly,
                                               final List<VideoStream> muxed) {
        final List<String> descriptions = new ArrayList<>();
        for (final VideoStream stream : concat(videoOnly, muxed)) {
            descriptions.add(heightOf(stream) + "p/" + fmt(stream.getFormat()) + "/"
                    + (stream.getCodec() == null ? "?" : stream.getCodec()));
            if (descriptions.size() >= 6) {
                break;
            }
        }
        return descriptions.isEmpty() ? "none" : String.join(", ", descriptions);
    }

    private static String describeAudioStreams(final List<AudioStream> audio) {
        final List<String> descriptions = new ArrayList<>();
        for (final AudioStream stream : audio) {
            descriptions.add(fmt(stream.getFormat()) + "/"
                    + (stream.getCodec() == null ? "?" : stream.getCodec()));
            if (descriptions.size() >= 6) {
                break;
            }
        }
        return descriptions.isEmpty() ? "none" : String.join(", ", descriptions);
    }

    private static String fmt(final MediaFormat format) {
        return format == null ? "?" : format.getName();
    }

    private static <T> List<T> safe(final List<T> list) {
        return list == null ? List.of() : list;
    }

    @SafeVarargs
    private static <T> List<T> concat(final List<T>... lists) {
        final List<T> result = new ArrayList<>();
        for (final List<T> list : lists) {
            result.addAll(list);
        }
        return result;
    }
}
