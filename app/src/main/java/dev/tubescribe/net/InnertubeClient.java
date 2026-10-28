package dev.tubescribe.net;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import dev.tubescribe.media.Quality;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct InnerTube fallback that bypasses the usual web page scraping.
 *
 * <p>NewPipeExtractor works through the regular web client, which YouTube
 * sometimes gates behind a "Sign in to confirm you're not a bot" challenge for
 * some networks. This client calls the same {@code /youtubei/v1/player}
 * endpoint with several less-gated InnerTube clients in sequence (Android,
 * iOS, then visionOS), which currently serve cipher-free direct stream URLs
 * without the challenge — the same idea Wave Archive uses for its visionOS
 * client, extended because VISIONOS itself is now bot-checked on some
 * networks. It is only consulted when the extractor fails.</p>
 */
public final class InnertubeClient {
    private static final Logger LOG = Logger.getLogger(InnertubeClient.class.getName());

    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
                    + "&key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Pattern VISITOR_DATA =
            Pattern.compile("\"(?:visitorData|VISITOR_DATA)\"\\s*:\\s*\"([A-Za-z0-9_%=\\-.]{20,})\"");

    /** One InnerTube client profile; tried in order until one is served. */
    private record ClientProfile(String name, String version, String userAgent,
                                 java.util.Map<String, Object> extra) {
        java.util.Map<String, Object> clientFields() {
            final java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("clientName", name);
            fields.put("clientVersion", version);
            fields.put("hl", "en");
            fields.put("gl", "US");
            fields.putAll(extra);
            return fields;
        }
    }

    private static final List<ClientProfile> CLIENTS = List.of(
            new ClientProfile("ANDROID", "20.10.38",
                    "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
                    java.util.Map.of("androidSdkVersion", 34)),
            new ClientProfile("IOS", "20.10.4",
                    "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
                    java.util.Map.of("deviceMake", "Apple", "deviceModel", "iPhone16,2",
                            "osName", "iPhone", "osVersion", "17.5.1.21F90")),
            new ClientProfile("VISIONOS", "1.02",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                            + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                    java.util.Map.of("deviceMake", "Apple", "deviceModel", "RealityDevice17,1",
                            "osName", "visionOS", "osVersion", "26.5.23O471")));

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    /**
     * Resolves direct video + audio stream URLs for the video via the InnerTube
     * player. Returns {@code null} when this path cannot serve the video, so
     * callers can fall back or surface their own error.
     */
    public VideoInfo resolve(final String videoId, final Quality quality) {
        String visitorData = fetchVisitorData(videoId);
        for (final ClientProfile profile : CLIENTS) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    final JsonObject player = player(profile, videoId, visitorData);
                    final VideoInfo info = pick(player, videoId, quality);
                    if (info != null) {
                        LOG.info("InnerTube fallback (" + profile.name() + ") resolved "
                                + videoId + " (" + info.qualityLabel() + ")");
                        return info;
                    }
                } catch (final IOException e) {
                    LOG.info("InnerTube " + profile.name() + " attempt " + (attempt + 1)
                            + " failed: " + e.getMessage());
                }
                // A blocked first answer often means a stale visitor id; refresh
                // it and try once more before moving to the next client.
                visitorData = fetchVisitorData(videoId);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- transport

    private JsonObject player(final ClientProfile profile, final String videoId,
                              final String visitorData) throws IOException {
        final java.util.Map<String, Object> innerClient = profile.clientFields();
        if (visitorData != null) {
            innerClient.put("visitorData", visitorData);
        }
        final java.util.Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("client", innerClient);
        context.put("user", new java.util.LinkedHashMap<String, Object>());

        final java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("context", context);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        final Request request = new Request.Builder()
                .url(PLAYER_URL)
                .header("User-Agent", profile.userAgent())
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JsonWriter.string(body), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            final String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("InnerTube player HTTP " + response.code());
            }
            final JsonObject json = JsonParser.object().from(text);
            final JsonObject status = json.getObject("playabilityStatus");
            if (status == null) {
                return json;
            }
            final String state = status.getString("status", "");
            if (!state.isEmpty() && !"OK".equals(state)) {
                throw new IOException("InnerTube playability status " + state);
            }
            return json;
        } catch (final JsonParserException e) {
            throw new IOException("InnerTube returned unreadable JSON: " + e.getMessage(), e);
        }
    }

    private static final String PAGE_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    private String fetchVisitorData(final String videoId) {
        final Request request = new Request.Builder()
                .url(WATCH_URL + videoId)
                .header("User-Agent", PAGE_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            final String html = response.body() == null ? "" : response.body().string();
            final Matcher matcher = VISITOR_DATA.matcher(html);
            return matcher.find() ? matcher.group(1) : null;
        } catch (final IOException | IllegalStateException e) {
            LOG.fine("No visitor data scraped: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------- selection

    /** @return resolved streams, or {@code null} when the answer has no usable streams. */
    private static VideoInfo pick(final JsonObject player, final String videoId,
                                  final Quality quality) throws IOException {
        final JsonObject streaming = player.getObject("streamingData");
        final JsonObject details = player.getObject("videoDetails");
        if (streaming == null || details == null) {
            return null;
        }
        final JsonArray formats = streaming.getArray("adaptiveFormats");
        if (formats == null || formats.isEmpty()) {
            return null;
        }
        JsonObject bestVideo = null;
        JsonObject bestAudio = null;
        for (int i = 0; i < formats.size(); i++) {
            final JsonObject format = formats.getObject(i);
            if (format.getString("url", null) == null) {
                continue; // signature-protected; not usable here
            }
            final String mime = format.getString("mimeType", "");
            if (mime.startsWith("video/mp4") && mime.contains("avc")) {
                final int height = format.getInt("height", 0);
                if (height > 0 && height <= quality.maxHeight() && isBetter(format, bestVideo)) {
                    bestVideo = format;
                }
            } else if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) {
                if (isBetter(format, bestAudio)) {
                    bestAudio = format;
                }
            }
        }

        final String title = details.getString("title", videoId);
        final String author = details.getString("author", "");
        final long duration = parseLongSafe(details.getString("lengthSeconds", "0"));

        if (bestVideo != null && bestAudio != null) {
            final int height = bestVideo.getInt("height", 0);
            final int fps = bestVideo.getInt("fps", 0);
            return new VideoInfo(videoId, title, author, duration, WATCH_URL + videoId, null,
                    bestVideo.getString("url", null), bestAudio.getString("url", null),
                    height, fps, height + "p" + (fps > 40 ? Integer.toString(fps) : ""));
        }

        // No separate pair; fall back to a muxed H.264/AAC progressive stream.
        JsonObject bestMuxed = null;
        for (int i = 0; i < formats.size(); i++) {
            final JsonObject format = formats.getObject(i);
            if (format.getString("url", null) == null) {
                continue;
            }
            final String mime = format.getString("mimeType", "");
            if (mime.startsWith("video/mp4") && mime.contains("avc") && mime.contains("mp4a")) {
                final int height = format.getInt("height", 0);
                if (height > 0 && height <= quality.maxHeight() && isBetter(format, bestMuxed)) {
                    bestMuxed = format;
                }
            }
        }
        final JsonArray muxedFormats = streaming.getArray("formats");
        if (bestMuxed == null && muxedFormats != null) {
            for (int i = 0; i < muxedFormats.size(); i++) {
                final JsonObject format = muxedFormats.getObject(i);
                if (format.getString("url", null) == null) {
                    continue;
                }
                final String mime = format.getString("mimeType", "");
                if (mime.startsWith("video/mp4") && mime.contains("avc") && mime.contains("mp4a")) {
                    final int height = format.getInt("height", 0);
                    if (height > 0 && height <= quality.maxHeight() && isBetter(format, bestMuxed)) {
                        bestMuxed = format;
                    }
                }
            }
        }
        if (bestMuxed != null) {
            final int height = bestMuxed.getInt("height", 0);
            final int fps = bestMuxed.getInt("fps", 0);
            return new VideoInfo(videoId, title, author, duration, WATCH_URL + videoId,
                    bestMuxed.getString("url", null), null, null, height, fps,
                    height + "p" + (fps > 40 ? Integer.toString(fps) : ""));
        }
        return null;
    }

    /** Higher bitrate (audio) or height+fps (video) wins; ties keep the first. */
    private static boolean isBetter(final JsonObject candidate, final JsonObject current) {
        if (current == null) {
            return true;
        }
        final int comparison = Integer.compare(rank(candidate), rank(current));
        return comparison > 0
                || (comparison == 0
                && candidate.getInt("itag", 0) > current.getInt("itag", 0));
    }

    private static int rank(final JsonObject format) {
        final int fps = format.getInt("fps", 0);
        final int height = format.getInt("height", 0);
        final int bitrate = format.getInt("bitrate", 0);
        if (height > 0) {
            return height * 100 + Math.min(fps, 60);
        }
        return bitrate / 10_000;
    }

    private static long parseLongSafe(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
