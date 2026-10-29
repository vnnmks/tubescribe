package dev.tubescribe.transcribe;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import dev.tubescribe.TubeApp;
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
 * limit, and chunks are sequential: chunk {@code i+1}'s file starts exactly
 * where chunk {@code i}'s file ends, with no special head. The accepted tail
 * text rides along as the Whisper {@code prompt}, so a chunk's conditioning
 * text ends precisely where its audio begins: the model transcribes a
 * continuation, not a replay. (Earlier builds kept a rewound head that
 * echoed the prompt text in audio; whisper suppresses prompt echoes, and the
 * suppression swallowed the phrase right after them — at 01:50 of the
 * reference video "intend consciously" vanished from every overlapping-chunk
 * transcript, since replaced by chunk-level seam words trimmed of phantom
 * completions.) Each chunk is posted as multipart form data with
 * {@code response_format=verbose_json} and word granularities, so segments
 * carry chunk-relative timestamps and every word its own start/end: cue
 * timing comes from the words a cue shows, not from a split guess. Cue
 * transcription ownership is a clean partition by chunk file boundaries:
 * each chunk owns its own file's audio outright. The floor partition stays
 * as jitter handling — a returned segment time may land a hair before the
 * file start — and a floor-dropped segment that runs past the floor still
 * bridges the gap left where the previous chunk's file ended mid-sentence.</p>
 * All configured API keys are tried in order: rate limits move the request
 * to the next key and rejected keys are retired for the rest of the session.</p>
 */
public final class GroqTranscriber {
    private static final Logger LOG = Logger.getLogger(GroqTranscriber.class.getName());
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL_ID = "whisper-large-v3-turbo";
    private static final MediaType AUDIO_M4A = MediaType.parse("audio/mp4");

    private static final long RETRY_BACKOFF_MS = 2_000;
    private static final int MAX_SAME_KEY_RETRIES = 2;
    /** Segments starting this close to the last accepted cue end are treated
     * as timing-jitter duplicates of audio already transcribed. */
    private static final double DUPLICATE_SLACK_SEC = 0.05;
    /** A segment that starts in the head zone (before the ownership floor)
     * counts as a seam bridge when it runs this far past the floor: below
     * that, whisper's segment end merely grazes it with no phrase-carrying
     * words behind (the word-span gate still applies). The gate is not a
     * duplicate guard: everything at and after the floor is audio the
     * previous chunk never heard, because its file ended there — and that
     * end-of-file zone is exactly where whisper completes a mid-phrase
     * truncation with hallucinated text, so the next chunk's continuation
     * must not be discarded even into the second second. */
    private static final double BRIDGE_MIN_EXTEND_SEC = 1.0;
    /** Bridges shorter than this are not worth a cue. */
    private static final double BRIDGE_MIN_SPAN_SEC = 1.0;
    /** Earliest a following owned segment may start relative to the bridge
     * it follows, so the dup rule does not swallow real speech. */
    private static final double BRIDGE_GUARD_SEC = 0.15;
    /** Upper bound on a bridge span; a stretch artefact longer than this
     * carries no trustworthy timing at all. */
    private static final double BRIDGE_CAP_SEC = 30.0;
    /** Word timestamps this far past the chunk's audio end cannot name real
     * audio: whisper sometimes completes a file that ends mid-phrase with
     * hallucinated words placed beyond the end. Such trailing words are
     * dropped before cue building. */
    private static final double MAX_WORD_PAST_END_SEC = 0.15;
    /** Tail text handed to the next request as Whisper's prompt continuation. */
    private static final int PROMPT_CHARS = 200;

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
        double acceptedEndSec = 0;
        String prompt = null;
        final boolean verbose = debugLoggingEnabled();
        for (int i = 0; i < total; i++) {
            cancel.throwIfCancelled();
            final dev.tubescribe.media.AudioChunker.Chunk chunk = chunks.get(i);
            final double offset = chunk.startUs() / 1_000_000.0;
            final double chunkEnd = (chunk.startUs() + chunk.durationUs()) / 1_000_000.0;
            listener.update(i / (double) total,
                    "Groq chunk " + (i + 1) + "/" + total + " · uploading "
                            + Fmt.time(offset) + "–" + Fmt.time(chunkEnd));
            if (verbose) {
                LOG.info("Groq chunk " + (i + 1) + "/" + total + ": container "
                        + Fmt.time(offset) + "–" + Fmt.time(chunkEnd)
                        + " (duration " + Fmt.time(chunk.durationUs() / 1_000_000.0)
                        + "), prompt=" + (prompt == null ? "none" : prompt.length() + " chars"));
            }
            final String body = uploadChunk(chunk.file().toPath(), offset, chunkEnd,
                    prompt, keys, listener, i, total, cancel);
            // Cue ownership with sequential chunks: a chunk owns its whole
            // file outright — the file starts where the previous one ends, so
            // the partition IS the chunk sequence. The floor (the chunk's own
            // file start) only guards residual timing jitter, e.g. a segment
            // returned at −0.02 relative to the file; a floor-dropped tail
            // that runs past the floor bridges in (see parseCues).
            final double startFloorSec = i == 0 ? Double.NEGATIVE_INFINITY : offset;
            acceptedEndSec = parseCues(body, offset, startFloorSec, chunkEnd,
                    cues, acceptedEndSec, verbose, (i + 1) + "/" + total);
            prompt = promptTail(cues);
            listener.update((i + 1) / (double) total,
                    "Groq chunk " + (i + 1) + "/" + total + " · transcribed ("
                            + cues.size() + " cues so far)");
        }

        listener.update(1.0, "Transcription complete");
        LOG.info("Groq returned " + cues.size() + " segment cue(s) from " + total
                + " audio chunk(s)");
        cues.sort(SubtitleCue.byStart());
        // Whisper's word timestamps can jitter: a word's end sometimes sits
        // after the next word's start, and consecutive segments or chunks
        // overlap a little. No cue may hold after the next one begins —
        // overlays would favor the later cue and flicker the text.
        for (int i = 0; i < cues.size() - 1; i++) {
            final SubtitleCue cue = cues.get(i);
            final double limit = cues.get(i + 1).start() - 0.02;
            if (limit < cue.end() && limit > cue.start()) {
                cues.set(i, new SubtitleCue(cue.start(), limit, cue.text()));
            }
        }
        return cues;
    }

    /**
     * The last {@value #PROMPT_CHARS} characters of accepted transcript text,
     * fed to the next request as Whisper's {@code prompt} so chunks continue
     * in the same voice and formatting instead of starting from scratch.
     * Returns {@code null} while nothing has been accepted yet.
     */
    private static String promptTail(final List<SubtitleCue> cues) {
        if (cues.isEmpty()) {
            return null;
        }
        final StringBuilder tail = new StringBuilder();
        for (int i = cues.size() - 1; i >= 0 && tail.length() < 2 * PROMPT_CHARS; i--) {
            final String text = cues.get(i).text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (tail.length() > 0) {
                tail.insert(0, ' ');
            }
            tail.insert(0, text);
        }
        final String result = tail.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        return result.length() > PROMPT_CHARS
                ? result.substring(result.length() - PROMPT_CHARS) : result;
    }

    // ------------------------------------------------------------------ upload

    private String uploadChunk(final Path file, final double startSec, final double endSec,
                               final String prompt, final List<String> keys,
                               final ProgressListener listener,
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
                final MultipartBody.Builder form = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model", MODEL_ID)
                        .addFormDataPart("response_format", "verbose_json")
                        // Word timestamps give each word its real start/end so
                        // cue text is timed by the words it shows; segments
                        // still drive ownership and the seam bridge.
                        .addFormDataPart("timestamp_granularities[]", "segment")
                        .addFormDataPart("timestamp_granularities[]", "word")
                        .addFormDataPart("temperature", "0")
                        .addFormDataPart("file", "audio.m4a", fileBody);
                if (prompt != null && !prompt.isBlank()) {
                    form.addFormDataPart("prompt", prompt);
                }
                final Request request = new Request.Builder()
                        .url(ENDPOINT)
                        .header("Authorization", "Bearer " + key)
                        .header("Accept", "application/json")
                        .post(form.build())
                        .build();
                listener.update((chunk + 0.45) / total,
                        "Groq chunk " + (chunk + 1) + "/" + total + " · transcribing via " + masked);
                final okhttp3.Call call = client.newCall(request);
                // Cancelling the run aborts this upload instead of waiting for
                // Groq to answer — checked between chunks otherwise.
                final Runnable removeHook = cancel.addHook(call::cancel);
                try (Response response = call.execute()) {
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
                } finally {
                    removeHook.run();
                }
            } catch (final IOException e) {
                if (cancel.isCancelled()) {
                    // A cancelled call surfaces as an IOException: report the
                    // cancel instead of rotating keys after a backoff sleep.
                    cancel.throwIfCancelled();
                }
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

    /**
     * Converts one chunk's response into cues, keeping only segments starting
     * at or after the chunk's ownership floor (the previous chunk's file end),
     * plus a seam bridge for floor-dropped segments that run past it.
     *
     * @param offset         global PTS of the chunk's first sample (seconds)
     * @param startFloorSec  segments starting before it are timestamp jitter
     *                       (sequential files start here; a normal response
     *                       has nothing before the floor); {@code -inf} for
     *                       the first chunk
     * @param chunkEndSec    end of the chunk's audio on the global timeline;
     *                       no real cue can outlive it
     * @param acceptedEndSec end of the last cue accepted so far; segments
     *                       starting inside it are timing-jitter duplicates
     * @param verbose        debug-logging gate (each segment + drop reason)
     * @param tag            chunk position label, e.g. {@code 2/5}
     * @return the updated end of the last accepted cue
     */
    double parseCues(final String body, final double offset,      // visible for tests
                              final double startFloorSec, final double chunkEndSec,
                              final List<SubtitleCue> cues, final double acceptedEndSec,
                              final boolean verbose, final String tag)
            throws IOException {
        final int firstNew = cues.size();
        double latestEnd = acceptedEndSec;
        try {
            final JsonObject json = JsonParser.object().from(body);
            final JsonArray segments = json.getArray("segments");
            final JsonArray words = json.getArray("words");
            if (verbose) {
                LOG.info("Groq " + tag + ": offset=" + Fmt.time(offset) + " ownership from "
                        + (startFloorSec < 0 ? "00:00" : Fmt.time(startFloorSec))
                        + ", whisper duration=" + Fmt.time(json.getDouble("duration", -1))
                        + ", words=" + (words == null ? "none" : String.valueOf(words.size()))
                        + ", text=" + clip(json.getString("text", ""), 40));
            }
            if (segments == null || segments.isEmpty()) {
                // No segment timestamps: the top-level text covers the chunk.
                // With sequential files the fallback only ever fires for the
                // first chunk (a floor at the file start would drop it); a
                // wordless tail chunk then contributes nothing but its
                // retranscribed owner still covers the audio it trimmed.
                final String text = json.getString("text", "").strip();
                final double duration = json.getDouble("duration", 30.0);
                if (!text.isEmpty() && startFloorSec < offset && acceptedEndSec <= offset) {
                    cues.add(new SubtitleCue(offset, offset + duration, text));
                    latestEnd = Math.max(latestEnd, offset + duration);
                }
                return latestEnd;
            }
            // Pass 1 (chunked transcriptions after the first): find a seam
            // bridge. A segment that starts below the ownership floor but ends
            // well past it covers audio the previous chunk could not
            // transcribe — its file ended mid-sentence at the floor. Admit
            // that audio from the floor on, clipped before the first owned
            // segment so the dup rule cannot swallow the speech that follows.
            if (!Double.isInfinite(startFloorSec)) {
                double firstOwnedStart = -1;
                double bridgeEnd = -1;
                String bridgeText = "";
                for (int s = 0; s < segments.size(); s++) {
                    final JsonObject segment = segments.getObject(s);
                    final double start = offset + Math.max(0, segment.getDouble("start", 0));
                    final double end = offset + Math.max(0, segment.getDouble("end", 0));
                    if (start >= startFloorSec) {
                        if (firstOwnedStart < 0) {
                            firstOwnedStart = start;
                        }
                    } else if (end > startFloorSec + BRIDGE_MIN_EXTEND_SEC
                            && end > bridgeEnd) {
                        bridgeEnd = end;
                        bridgeText = segment.getString("text", "").strip();
                    }
                }
                double clipped = bridgeEnd < 0 ? -1
                        : Math.min(bridgeEnd, startFloorSec + BRIDGE_CAP_SEC);
                if (firstOwnedStart > 0) {
                    clipped = Math.min(clipped, firstOwnedStart - BRIDGE_GUARD_SEC);
                }
                if (!bridgeText.isEmpty() && clipped - startFloorSec >= BRIDGE_MIN_SPAN_SEC) {
                    final List<dev.tubescribe.subs.CueBuilder.WordSpan> bridgeWords =
                            trimPhantomWords(
                                    wordSpans(words, offset, startFloorSec, clipped),
                                    chunkEndSec);
                    if (words != null && words.size() > 0 && bridgeWords.isEmpty()) {
                        // Word timestamps exist, but none land in the
                        // overhang: bridging by segment text would re-show
                        // audio the previous chunk already transcribed, with
                        // invented timing. The owned segments carry the
                        // reliable continuation.
                        if (verbose) {
                            LOG.info("Groq " + tag + " bridge skipped: no words past "
                                    + Fmt.time(startFloorSec));
                        }
                    } else if (bridgeWords.isEmpty()) {
                        cues.addAll(dev.tubescribe.subs.CueBuilder.fromSegment(
                                startFloorSec, clipped - startFloorSec, bridgeText));
                        if (verbose) {
                            LOG.info("Groq " + tag + " bridge: " + Fmt.time(startFloorSec)
                                    + "–" + Fmt.time(clipped) + " | " + clip(bridgeText, 160)
                                    + " (text only)");
                        }
                    } else {
                        cues.addAll(dev.tubescribe.subs.CueBuilder.fromWords(bridgeWords));
                        if (verbose) {
                            LOG.info("Groq " + tag + " bridge: " + Fmt.time(startFloorSec)
                                    + "–" + Fmt.time(clipped) + " | " + clip(bridgeText, 160)
                                    + " (" + bridgeWords.size() + " words)");
                        }
                    }
                    latestEnd = Math.max(latestEnd, Math.min(clipped, chunkEndSec));
                }
            }
            // Pass 2: decide ownership, drop duplicates, and mark the owned
            // time window.
            double ownedFrom = -1;
            double ownedTo = -1;
            double acceptedThrough = latestEnd;
            final List<Integer> owned = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                final JsonObject segment = segments.getObject(i);
                final double start = offset + Math.max(0, segment.getDouble("start", 0));
                final double end = offset + Math.max(0, segment.getDouble("end", 0));
                final String text = segment.getString("text", "").strip();
                if (text.isEmpty()) {
                    continue;
                }
                final boolean mine = start >= startFloorSec;
                final boolean duplicate = acceptedThrough > 0
                        && start < acceptedThrough - DUPLICATE_SLACK_SEC;
                if (verbose) {
                    LOG.info("Groq " + tag + " seg " + (i + 1) + ": " + Fmt.time(start)
                            + "–" + Fmt.time(end) + " | " + clip(text, 48)
                            + (mine ? "" : " [FLOOR-DROP]")
                            + (duplicate ? " [DUP-DROP]" : ""));
                }
                if (!mine || duplicate) {
                    continue;
                }
                owned.add(i);
                if (ownedFrom < 0 || start < ownedFrom) {
                    ownedFrom = start;
                }
                ownedTo = Math.max(ownedTo, end);
                acceptedThrough = Math.max(acceptedThrough, end);
            }
            // Cue building runs once over every owned word in the territory
            // instead of once per segment: whisper's segment cuts sit
            // mid-sentence, so a straddling word lands in the previous
            // segment's word window while its phrase continues in the next;
            // grouping there strands it in a one-word cue that flashes by
            // unreadably. One continuous pass keeps phrases whole, and the
            // spoken words still drive cue timing directly.
            final List<dev.tubescribe.subs.CueBuilder.WordSpan> ownedWords = ownedFrom < 0
                    ? List.of()
                    : trimPhantomWords(
                            wordSpans(words, offset, ownedFrom - 0.02, ownedTo + 0.02),
                            chunkEndSec);
            if (!ownedWords.isEmpty()) {
                cues.addAll(dev.tubescribe.subs.CueBuilder.fromWords(ownedWords));
                latestEnd = Math.max(latestEnd, Math.min(ownedTo, chunkEndSec));
            } else {
                // Character-share fallback: no word list came back (or none
                // inside the owned territory), so split each owned segment's
                // text by character count as before.
                for (final int i : owned) {
                    final JsonObject segment = segments.getObject(i);
                    final double start = offset + Math.max(0, segment.getDouble("start", 0));
                    final double end = offset + Math.max(0, segment.getDouble("end", 0));
                    final String text = segment.getString("text", "").strip();
                    cues.addAll(dev.tubescribe.subs.CueBuilder.fromSegment(
                            start, Math.max(end, start + 0.4) - start, text));
                }
                if (ownedFrom >= 0) {
                    latestEnd = Math.max(latestEnd, Math.min(ownedTo, chunkEndSec));
                }
            }
        } catch (final com.grack.nanojson.JsonParserException e) {
            throw new IOException("Groq returned an unreadable response: " + e.getMessage(), e);
        }
        // No audio exists past the chunk's file end: a cue may not claim
        // that time, however whisper's segment growth placed it.
        for (int i = firstNew; i < cues.size(); i++) {
            final SubtitleCue cue = cues.get(i);
            if (cue.end() > chunkEndSec) {
                cues.set(i, new SubtitleCue(cue.start(), chunkEndSec, cue.text()));
            }
        }
        return latestEnd;
    }

    /** Drops trailing word timestamps that claim the chunk's audio kept
     * speaking past its file end. When the chunker cuts mid-phrase, whisper
     * sometimes completes the truncation with hallucinated text — placed
     * beyond the end it had no audio for — and the last words' timestamps
     * run past the end with it: that text is a model guess, not speech, and
     * the following chunk transcribes that audio properly from the exact
     * point the file ended. */
    private static List<dev.tubescribe.subs.CueBuilder.WordSpan> trimPhantomWords(
            final List<dev.tubescribe.subs.CueBuilder.WordSpan> words,
            final double chunkEndSec) {
        final double limit = chunkEndSec + MAX_WORD_PAST_END_SEC;
        int last = words.size();
        while (last > 0 && words.get(last - 1).end() > limit) {
            last--;
        }
        return last == words.size() ? words : words.subList(0, last);
    }

    /** Short single-line text prefix for debug logs. */
    private static String clip(final String text, final int max) {
        final String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * Collects the chunk's word timestamps whose start lies inside
     * {@code [from, to)}, shifted into the global timeline.
     * @param words  the response's optional word list, or null
     * @param offset global PTS of the chunk's first sample (seconds)
     */
    private static List<dev.tubescribe.subs.CueBuilder.WordSpan> wordSpans(
            final JsonArray words, final double offset, final double from, final double to) {
        final List<dev.tubescribe.subs.CueBuilder.WordSpan> out = new ArrayList<>();
        if (words == null) {
            return out;
        }
        for (int i = 0; i < words.size(); i++) {
            final JsonObject word = words.getObject(i);
            final double relStart = Math.max(0, word.getDouble("start", 0));
            final double relEnd = Math.max(relStart, word.getDouble("end", relStart + 0.15));
            final double start = offset + relStart;
            final double end = offset + relEnd;
            if (start < from - 0.02 || start >= to) {
                continue;
            }
            final String raw = word.getString("word", "");
            if (!raw.isEmpty()) {
                out.add(new dev.tubescribe.subs.CueBuilder.WordSpan(raw, start, end));
            }
        }
        return out;
    }

    /** Verbose segment logging only while the debug chunk limit is active. */
    private static boolean debugLoggingEnabled() {
        try {
            return TubeApp.debugChunkLimit() > 0;
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
