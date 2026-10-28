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
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
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
 * <p>The 16 kHz mono WAV is split at the quietest points into chunks below the
 * 25 MB upload limit. Each chunk is posted as multipart form data with
 * {@code response_format=verbose_json} so segments carry absolute-in-chunk
 * timestamps, which are then offset by the chunk start. All configured API
 * keys are tried in order: rate limits move the request to the next key and
 * rejected keys are retired for the rest of the session.</p>
 */
public final class GroqTranscriber {
    private static final Logger LOG = Logger.getLogger(GroqTranscriber.class.getName());
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL_ID = "whisper-large-v3-turbo";
    private static final MediaType AUDIO_WAV = MediaType.parse("audio/wav");

    private static final int SAMPLE_RATE = 16_000;
    private static final double HOP_SECONDS = 0.1;
    private static final int HOP_FRAMES = (int) Math.round(HOP_SECONDS * SAMPLE_RATE);
    private static final double MIN_CHUNK_SECONDS = 150.0;
    private static final double MAX_CHUNK_SECONDS = 580.0; // ≈18.6 MB WAV, below the 25 MB limit

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

    public List<SubtitleCue> transcribe(final Path wav, final ProgressListener listener,
                                        final Cancellation cancel) throws IOException {
        if (keys.isEmpty()) {
            throw new IOException("No Groq API keys configured. Add at least one key "
                    + "in Settings → Groq API keys.");
        }
        LOG.info("Groq cloud transcription with " + keys.size() + " configured key(s)");
        listener.update(0.0, "Finding quiet spots in the audio…");
        final List<long[]> chunks = splitWav(wav, listener, cancel);
        LOG.info("Groq upload plan: " + chunks.size() + " chunk(s) for " + wav.getFileName());

        final List<SubtitleCue> cues = new ArrayList<>();
        final int total = chunks.size();
        for (int i = 0; i < total; i++) {
            cancel.throwIfCancelled();
            final long[] chunk = chunks.get(i);
            final Path chunkFile = wav.resolveSibling(
                    String.format(Locale.ROOT, "groq-%04d.wav", i));
            final double offset = chunk[0] / (double) SAMPLE_RATE;
            final double chunkEnd = chunk[1] / (double) SAMPLE_RATE;
            try {
                listener.update(i / (double) total,
                        "Groq chunk " + (i + 1) + "/" + total + " · uploading "
                                + Fmt.time(offset) + "–" + Fmt.time(chunkEnd));
                final String body = uploadChunk(chunkFile, chunk[0], chunk[1],
                        keys, listener, i, total, cancel);
                parseCues(body, offset, cues);
                listener.update((i + 1) / (double) total,
                        "Groq chunk " + (i + 1) + "/" + total + " · transcribed ("
                                + cues.size() + " cues so far)");
            } finally {
                try {
                    Files.deleteIfExists(chunkFile);
                } catch (final IOException e) {
                    LOG.fine("Could not remove chunk file: " + e.getMessage());
                }
            }
        }

        listener.update(1.0, "Transcription complete");
        LOG.info("Groq returned " + cues.size() + " segment cue(s) from " + total
                + " chunk(s) of " + wav.getFileName());
        cues.sort(SubtitleCue.byStart());
        return cues;
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Writes quiet-point-split WAV fragment files and returns the chunk list
     * as {@code long[]{startFrame, endFrame}} pairs.
     */
    private List<long[]> splitWav(final Path wav, final ProgressListener listener,
                                  final Cancellation cancel) throws IOException {
        final double[] hopRms = waveformEnergy(wav, listener, cancel);
        final long totalFrames = hopRms.length <= 0
                ? 0 : (long) Math.ceil(hopRms.length * HOP_FRAMES);
        final int maxChunkFrames = (int) (MAX_CHUNK_SECONDS * SAMPLE_RATE);
        final int minChunkFrames = (int) (MIN_CHUNK_SECONDS * SAMPLE_RATE);

        final List<long[]> chunks = new ArrayList<>();
        long start = 0;
        int startHop = 0;
        while (totalFrames - start > maxChunkFrames) {
            final int searchStart = startHop + minChunkFrames / HOP_FRAMES;
            final int searchEnd = Math.min(hopRms.length,
                    startHop + maxChunkFrames / HOP_FRAMES);
            int bestHop = -1;
            double bestRms = Double.MAX_VALUE;
            for (int hop = searchStart; hop < searchEnd; hop++) {
                if (hopRms[hop] < bestRms) {
                    bestRms = hopRms[hop];
                    bestHop = hop;
                }
            }
            final int cutHop = bestHop >= 0 ? bestHop : startHop + maxChunkFrames / HOP_FRAMES;
            final long cutFrame = Math.min(totalFrames,
                    Math.round(cutHop * HOP_SECONDS * SAMPLE_RATE));
            chunks.add(new long[]{start, cutFrame});
            start = cutFrame;
            startHop = cutHop;
        }
        if (totalFrames > start) {
            chunks.add(new long[]{start, totalFrames});
        }
        if (chunks.isEmpty()) {
            chunks.add(new long[]{0, Math.max(1, totalFrames)});
        }
        writeChunkFiles(wav, chunks, listener, cancel);
        return chunks;
    }

    /** Per-0.1 s RMS levels of the WAV, used to pick silence-anchored cuts. */
    private double[] waveformEnergy(final Path wav, final ProgressListener listener,
                                    final Cancellation cancel) throws IOException {
        final List<Double> rms = new ArrayList<>();
        try (WavSource source = WavSource.open(wav)) {
            final float[] buffer = new float[HOP_FRAMES];
            while (true) {
                cancel.throwIfCancelled();
                final int read = source.read(buffer);
                if (read <= 0) {
                    break;
                }
                double sum = 0;
                for (int i = 0; i < read; i++) {
                    sum += buffer[i] * (double) buffer[i];
                }
                rms.add(read <= 0 ? 0 : Math.sqrt(sum / read));
            }
        }
        final double[] values = new double[rms.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = rms.get(i);
        }
        return values;
    }

    /** One buffered sequential pass writes every fragment file. */
    private void writeChunkFiles(final Path wav, final List<long[]> chunks,
                                 final ProgressListener listener,
                                 final Cancellation cancel) throws IOException {
        final List<Path> files = new ArrayList<>();
        final List<RandomAccessFile> headers = new ArrayList<>();
        final List<OutputStream> streams = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            final Path target = wav.resolveSibling(
                    String.format(Locale.ROOT, "groq-%04d.wav", i));
            files.add(target);
            final RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw");
            raf.setLength(0);
            writeRiffHeader(raf, (int) (chunks.get(i)[1] - chunks.get(i)[0]));
            headers.add(raf);
            streams.add(new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(target.toFile(), true)));
        }
        try (WavSource source = WavSource.open(wav)) {
            final float[] buffer = new float[SAMPLE_RATE]; // one second at a time
            long framePos = 0;
            int chunk = 0;
            while (true) {
                cancel.throwIfCancelled();
                final int read = source.read(buffer);
                if (read <= 0) {
                    break;
                }
                final byte[] pcm = new byte[read * 2];
                for (int i = 0; i < read; i++) {
                    final float f = Math.max(-1f, Math.min(1f, buffer[i]));
                    final int sample = Math.round(f * 32_767f);
                    pcm[2 * i] = (byte) sample;
                    pcm[2 * i + 1] = (byte) (sample >> 8);
                }
                int cursor = 0;
                while (cursor < read) {
                    final long chunkEnd = chunks.get(chunk)[1];
                    final int take = (int) Math.min(read - cursor, chunkEnd - framePos);
                    streams.get(chunk).write(pcm, cursor * 2, take * 2);
                    framePos += take;
                    cursor += take;
                    if (framePos >= chunkEnd && chunk < chunks.size() - 1) {
                        streams.get(chunk).flush();
                        chunk++;
                    }
                }
                final double progress = Math.min(0.98, framePos / Math.max(1.0,
                        (double) chunks.get(chunks.size() - 1)[1]));
                listener.update(progress, "Preparing cloud upload · "
                        + Fmt.time(framePos / (double) SAMPLE_RATE) + " / "
                        + Fmt.time(chunks.get(chunks.size() - 1)[1] / (double) SAMPLE_RATE));
            }
        } finally {
            for (final OutputStream stream : streams) {
                stream.flush();
                stream.close();
            }
            for (final RandomAccessFile raf : headers) {
                raf.close();
            }
        }
    }

    private static void writeRiffHeader(final RandomAccessFile file, final int frames)
            throws IOException {
        final int dataLength = frames * 2;
        final byte[] header = new byte[44];
        ascii(header, 0, "RIFF");
        le32(header, 4, 36 + dataLength);
        ascii(header, 8, "WAVE");
        ascii(header, 12, "fmt ");
        le32(header, 16, 16);
        le16(header, 20, 1);              // PCM
        le16(header, 22, 1);              // mono
        le32(header, 24, SAMPLE_RATE);
        le32(header, 28, SAMPLE_RATE * 2);
        le16(header, 32, 2);              // block align
        le16(header, 34, 16);             // bits per sample
        ascii(header, 36, "data");
        le32(header, 40, dataLength);
        file.write(header);
    }

    private static void ascii(final byte[] target, final int offset, final String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void le16(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void le32(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // ------------------------------------------------------------------ upload

    private String uploadChunk(final Path file, final long startFrames, final long endFrames,
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
                final RequestBody fileBody = RequestBody.create(file.toFile(), AUDIO_WAV);
                final MultipartBody form = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model", MODEL_ID)
                        .addFormDataPart("response_format", "verbose_json")
                        .addFormDataPart("temperature", "0")
                        .addFormDataPart("file", "chunk.wav", fileBody)
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
                                + Fmt.time(startFrames / (double) SAMPLE_RATE) + "–"
                                + Fmt.time(endFrames / (double) SAMPLE_RATE)
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
                cues.add(new SubtitleCue(start, Math.max(end, start + 0.4), text));
            }
        } catch (final com.grack.nanojson.JsonParserException e) {
            throw new IOException("Groq returned an unreadable response: " + e.getMessage(), e);
        }
    }
}
