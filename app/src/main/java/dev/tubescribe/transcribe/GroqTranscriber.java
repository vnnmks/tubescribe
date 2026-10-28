package dev.tubescribe.transcribe;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import dev.tubescribe.subs.SubtitleCue;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.ProgressListener;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cloud transcription through Groq's OpenAI-compatible Whisper endpoint
 * ({@code whisper-large-v3-turbo}).
 *
 * <p>Audio arrives as pre-split {@code .m4a} chunks (see
 * {@link dev.tubescribe.media.AudioChunker}), each below the 25 MB upload
 * limit. Each chunk is posted as multipart form data with
 * {@code response_format=verbose_json} so segments carry absolute-in-chunk
 * timestamps, which are then offset by the chunk start. All configured API
 * keys are tried in order: rate limits move the request to the next key and
 * rejected keys are retired for the rest of the session.</p>
 */
public final class GroqTranscriber {
    private static final Logger LOG = Logger.getLogger(GroqTranscriber.class.getName());
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL_ID = "whisper-large-v3-turbo";
    private static final MediaType AUDIO_M4A = MediaType.parse("audio/mp4");

    private static final long RETRY_BACKOFF_MS = 2_000;
    private static final int MAX_SAME_KEY_RETRIES = 2;

    private final OkHttpClient client = buildClient();
    private final List<String> keys;

    public GroqTranscriber(final List<String> keys) {
        this.keys = keys;
    }

    private static OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.MINUTES)
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(15, TimeUnit.MINUTES)
                .build();
    }

    public List<SubtitleCue> transcribe(final List<dev.tubescribe.media.AudioChunker.Chunk> chunks,
                                        final ProgressListener listener,
                                        final Cancellation cancel) throws IOException {
        if (keys.isEmpty()) {
            throw new IOException("No Groq API keys configured. Add at least one key "
                    + "in Settings → Groq API keys.");
        }
        if (chunks.isEmpty()) {
            throw new IOException("No audio was prepared for transcription.");
        }
        LOG.info("Groq cloud transcription with " + keys.size() + " configured key(s), "
                + chunks.size() + " chunk(s)");
        final List<SubtitleCue> cues = new ArrayList<>();
        final int total = chunks.size();
        long offsetUs = 0;
        for (int i = 0; i < total; i++) {
            cancel.throwIfCancelled();
            final dev.tubescribe.media.AudioChunker.Chunk chunk = chunks.get(i);
            final double offset = offsetUs / 1_000_000.0;
            final double chunkEnd = (offsetUs + chunk.durationUs()) / 1_000_000.0;
            listener.update(i / (double) total,
                    "Groq chunk " + (i + 1) + "/" + total + " · uploading "
                            + Fmt.time(offset) + "–" + Fmt.time(chunkEnd));
            final String body = uploadChunk(chunk.file().toPath(), offset, chunkEnd,
                    keys, listener, i, total, cancel);
            parseCues(body, offset, cues);
            listener.update((i + 1) / (double) total,
                    "Groq chunk " + (i + 1) + "/" + total + " · transcribed ("
                            + cues.size() + " cues so far)");
            offsetUs += chunk.durationUs();
        }

        listener.update(1.0, "Transcription complete");
        LOG.info("Groq returned " + cues.size() + " segment cue(s) from " + total
                + " audio chunk(s)");
        cues.sort(SubtitleCue.byStart());
        return cues;
    }

    // ------------------------------------------------------------------ upload

    private String uploadChunk(final Path file, final double startSec, final double endSec,
                               final List<String> keys, final ProgressListener listener,
                               final int chunk, final int total,
                               final Cancellation cancel) throws IOException {
        final Set<Integer> dead = new HashSet<>();
        IOException lastError = null;
        int index = 0;
        int sameKeyRetries = 0;
        int attempts = 0;
        final int maxAttempts = keys.size() * 3 + 6;
        while (dead.size() < keys.size() && attempts < maxAttempts) {
            attempts++;
            cancel.throwIfCancelled();
            final String key = keys.get(index);
            final String masked = "key#" + (index + 1);
            try {
                final RequestBody fileBody = RequestBody.create(file.toFile(), AUDIO_M4A);
                final MultipartBody form = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model", MODEL_ID)
                        .addFormDataPart("response_format", "verbose_json")
                        .addFormDataPart("temperature", "0")
                        .addFormDataPart("file", "audio.m4a", fileBody)
                        .build();
                final Request request = new Request.Builder()
                        .url(ENDPOINT)
                        .header("Authorization", "Bearer " + key)
                        .header("Accept", "application/json")
                        .post(form)
                        .build();
                listener.update((chunk + 0.45) / total,
                        "Groq chunk " + (chunk + 1) + "/" + total + " · transcribing via " + masked);
                try (Response response = client.newCall(request).execute()) {
                    cancel.throwIfCancelled();
                    final String body = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) {
                        LOG.info("Groq chunk " + (chunk + 1) + "/" + total + " ("
                                + Fmt.time(startSec) + "–" + Fmt.time(endSec)
                                + ") succeeded via " + masked);
                        return body;
                    }
                    if (response.code() == 401 || response.code() == 403) {
                        final String detail = firstError(body);
                        LOG.warning("Groq rejected " + masked + " (HTTP "
                                + response.code() + "): " + detail
                                + "; retiring it for this session");
                        dead.add(index);
                        lastError = new IOException("HTTP " + response.code()
                                + ": the key was rejected — " + detail);
                    } else if (response.code() == 429) {
                        LOG.warning("Groq rate-limited " + masked + "; switching keys");
                        lastError = new IOException("HTTP 429: rate limit reached on " + masked);
                    } else {
                        final String message = firstError(body);
                        throw new IOException("Groq API error (HTTP " + response.code()
                                + "): " + message);
                    }
                }
            } catch (final IOException e) {
                if (dead.contains(index) || isStickyFailure(e)) {
                    // stable non-key failures rotate like rate limits do
                    lastError = e;
                    sameKeyRetries = 0;
                    index = nextKey(index, keys, dead);
                    continue;
                }
                sameKeyRetries++;
                lastError = e;
                if (sameKeyRetries <= MAX_SAME_KEY_RETRIES) {
                    LOG.log(Level.WARNING, "Groq upload attempt " + sameKeyRetries
                            + " failed on " + masked + "; retrying", e);
                    sleep(RETRY_BACKOFF_MS * sameKeyRetries);
                    continue;
                }
                sameKeyRetries = 0;
                LOG.warning("Groq upload keeps failing on " + masked + "; switching keys: "
                        + e.getMessage());
                index = nextKey(index, keys, dead);
            }
        }
        throw new IOException("All " + keys.size() + " Groq key(s) could not complete the "
                + "transcription. Last problem: "
                + (lastError == null ? "unknown error" : lastError.getMessage()), lastError);
    }

    private static boolean isStickyFailure(final IOException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("groq api error");
    }

    private static int nextKey(final int current, final List<String> keys,
                               final Set<Integer> dead) {
        for (int step = 1; step <= keys.size(); step++) {
            final int candidate = (current + step) % keys.size();
            if (!dead.contains(candidate)) {
                return candidate;
            }
        }
        return current;
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstError(final String body) {
        try {
            final JsonObject json = JsonParser.object().from(body);
            final JsonObject error = json.getObject("error");
            final String message = error == null ? null : error.getString("message", null);
            return message == null || message.isBlank()
                    ? (body.length() > 300 ? body.substring(0, 300) : body) : message;
        } catch (final com.grack.nanojson.JsonParserException e) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    // ------------------------------------------------------------------ parsing

    private void parseCues(final String body, final double offset,
                           final List<SubtitleCue> cues) throws IOException {
        try {
            final JsonObject json = JsonParser.object().from(body);
            final JsonArray segments = json.getArray("segments");
            if (segments == null || segments.isEmpty()) {
                final String text = json.getString("text", "").strip();
                if (!text.isEmpty()) {
                    final double duration = json.getDouble("duration", 30.0);
                    cues.add(new SubtitleCue(offset, offset + duration, text));
                }
                return;
            }
            for (int i = 0; i < segments.size(); i++) {
                final JsonObject segment = segments.getObject(i);
                final double start = offset + Math.max(0, segment.getDouble("start", 0));
                final double end = offset + Math.max(0, segment.getDouble("end", 0));
                final String text = segment.getString("text", "").strip();
                if (text.isEmpty()) {
                    continue;
                }
                // Split long segments so the subtitle overlay stays readable.
                cues.addAll(dev.tubescribe.subs.CueBuilder.fromSegment(
                        start, Math.max(end, start + 0.4) - start, text));
            }
        } catch (final com.grack.nanojson.JsonParserException e) {
            throw new IOException("Groq returned an unreadable response: " + e.getMessage(), e);
        }
    }
}
