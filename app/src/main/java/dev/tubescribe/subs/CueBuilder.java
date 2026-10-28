package dev.tubescribe.subs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a transcript segment and its time span into readable cues.
 *
 * <p>Cloud Whisper sometimes returns very long segments (a whole chorus as one
 * entry). Within a long segment the text is split at sentence and word
 * boundaries and the duration is distributed by character count, so the
 * subtitle overlay stays readable.</p>
 */
public final class CueBuilder {
    private static final int MAX_CHARS = 84;
    private static final double MAX_CUE_SECONDS = 7.0;
    private static final double MIN_CUE_SECONDS = 0.55;
    private static final Pattern NON_SPEECH = Pattern.compile(
            "^[\\[\\(]\\s*(music|applause|laughter|blank_audio|silence|noise|inaudible)[^\\]\\)]*[\\]\\)]$",
            Pattern.CASE_INSENSITIVE);

    private CueBuilder() {
    }

    public static List<SubtitleCue> fromSegment(final double start, final double duration,
                                                final String rawText) {
        final String text = clean(rawText);
        if (text.isEmpty() || duration <= 0.05) {
            return List.of();
        }

        int count = Math.max(
                (int) Math.ceil(text.length() / (double) MAX_CHARS),
                (int) Math.ceil(duration / MAX_CUE_SECONDS));
        count = Math.max(1, Math.min(count, Math.max(1, (int) (duration / MIN_CUE_SECONDS))));

        final List<String> parts = split(text, count);
        if (parts.isEmpty()) {
            return List.of();
        }

        int totalChars = 0;
        for (final String part : parts) {
            totalChars += part.length();
        }
        if (totalChars == 0) {
            return List.of();
        }

        final List<SubtitleCue> cues = new ArrayList<>(parts.size());
        double cursor = start;
        for (int i = 0; i < parts.size(); i++) {
            final String part = parts.get(i);
            final double share = duration * (part.length() / (double) totalChars);
            final double end = i == parts.size() - 1
                    ? start + duration
                    : Math.min(start + duration, cursor + Math.max(MIN_CUE_SECONDS, share));
            if (!part.isBlank() && end > cursor) {
                cues.add(new SubtitleCue(cursor, end, part));
            }
            cursor = end;
        }
        return cues;
    }

    /** One timed word from whisper's word-level timestamps. */
    public record WordSpan(String word, double start, double end) {
    }

    /** Break cue grouping when the next word waits longer than this. */
    private static final double PAUSE_BREAK_SEC = 0.6;
    /** Short word-timed cues are held up to this long so fast phrases do not
     * flash; never past the next cue's start. */
    private static final double MIN_HOLD_SEC = 1.6;
    /** Extra characters a fold may add on top of {@link #MAX_CHARS}: one
     * stranded word may push a full cue one word past the two-line target
     * rather than flash by as a one-word subtitle. */
    private static final int FOLD_SLACK_CHARS = 16;
    /** A fold may stretch a cue this far past {@link #MAX_CUE_SECONDS} to
     * absorb a stranded neighbor word. */
    private static final double FOLD_SLACK_SEC = 2.0;

    /**
     * Builds cues from whisper's word-level timestamps, so every cue is on
     * screen exactly while the words it carries are being spoken. Words
     * group into readable cues by size, duration, and natural pauses; the
     * fallback in {@link #fromSegment} guesses interior timing by character
     * count instead, which visibly drifts on the long segments cloud Whisper
     * returns.
     */
    public static List<SubtitleCue> fromWords(final List<WordSpan> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        final List<SubtitleCue> built = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        double groupStart = words.get(0).start();
        double groupEnd = groupStart;
        for (final WordSpan word : words) {
            if (!text.isEmpty()
                    && (text.length() >= MAX_CHARS
                    || word.start() - groupEnd >= PAUSE_BREAK_SEC
                    || word.end() - groupStart >= MAX_CUE_SECONDS)) {
                final String cleaned = clean(text.toString());
                if (!cleaned.isEmpty() && groupEnd > groupStart) {
                    built.add(new SubtitleCue(groupStart, groupEnd, cleaned));
                }
                text.setLength(0);
                groupStart = word.start();
            }
            appendWord(text, word.word());
            groupEnd = Math.max(groupEnd, word.end());
        }
        final String cleaned = clean(text.toString());
        if (!cleaned.isEmpty() && groupEnd > groupStart) {
            built.add(new SubtitleCue(groupStart, groupEnd, cleaned));
        }
        // Whisper strands the occasional single word as its own cue: a size
        // or pause flush fires right before it, and the next pause (or the
        // end of the word list) cuts it off behind. A one-word subtitle
        // flashes by too fast to read, so fold it into a neighbor — backward
        // when the previous cue can absorb it, otherwise forward when it
        // opens the batch.
        final List<SubtitleCue> folded = new ArrayList<>(built.size());
        for (int i = 0; i < built.size(); i++) {
            final SubtitleCue cue = built.get(i);
            if (cue.text().split("\\s+").length > 1) {
                folded.add(cue);
                continue;
            }
            if (!folded.isEmpty()) {
                final SubtitleCue prev = folded.get(folded.size() - 1);
                if (prev.text().length() + 1 + cue.text().length()
                        <= MAX_CHARS + FOLD_SLACK_CHARS
                        && cue.end() - prev.start() <= MAX_CUE_SECONDS + FOLD_SLACK_SEC) {
                    folded.set(folded.size() - 1, new SubtitleCue(prev.start(), cue.end(),
                            prev.text() + ' ' + cue.text()));
                    continue;
                }
                folded.add(cue);
                continue;
            }
            final SubtitleCue next = i + 1 < built.size() ? built.get(i + 1) : null;
            if (next != null
                    && cue.text().length() + 1 + next.text().length()
                    <= MAX_CHARS + FOLD_SLACK_CHARS
                    && next.end() - cue.start() <= MAX_CUE_SECONDS + FOLD_SLACK_SEC) {
                folded.add(new SubtitleCue(cue.start(), next.end(),
                        cue.text() + ' ' + next.text()));
                i++;
                continue;
            }
            folded.add(cue);
        }
        built.clear();
        built.addAll(folded);
        // Hold briefly on very short cues; never past the next cue's start.
        for (int i = 0; i < built.size(); i++) {
            final SubtitleCue cue = built.get(i);
            if (cue.duration() >= MIN_CUE_SECONDS) {
                continue;
            }
            final double limit = i + 1 < built.size()
                    ? Math.min(cue.start() + MIN_HOLD_SEC, built.get(i + 1).start() - 0.02)
                    : cue.start() + MIN_HOLD_SEC;
            if (limit > cue.end()) {
                built.set(i, new SubtitleCue(cue.start(), limit, cue.text()));
            }
        }
        return built;
    }

    /** Appends one raw word, keeping the spacing whisper already embedded. */
    private static void appendWord(final StringBuilder text, final String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        if (text.length() > 0) {
            final char first = raw.charAt(0);
            final boolean glued = Character.isWhitespace(first)
                    || first == ',' || first == '.' || first == '!' || first == '?'
                    || first == ';' || first == ':' || first == '…' || first == ')'
                    || first == '\'' || first == '’';
            if (!glued) {
                text.append(' ');
            }
        }
        text.append(raw);
    }

    static String clean(final String rawText) {        if (rawText == null) {
            return "";
        }
        String text = rawText
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        // Strip leading speaker dashes and decorative music notes.
        text = text.replaceAll("^[-–—]+\\s*", "");
        text = text.replaceAll("^[♪♫\\s]+", "").strip();
        if (text.isEmpty() || text.equals("♪") || NON_SPEECH.matcher(text).matches()) {
            return "";
        }
        // Whisper occasionally emits a bracketed marker at the start; drop the whole
        // cue only when nothing else remains after it.
        final String withoutMarkers = text
                .replaceAll("(?i)\\[(music|applause|laughter|blank_audio|silence|noise)\\]", "")
                .replaceAll("(?i)\\((music|applause|laughter|silence|noise)\\)", "")
                .strip();
        return withoutMarkers.isEmpty() ? "" : text;
    }

    /** Splits the text into roughly balanced word groups, preferring sentence ends. */
    static List<String> split(final String text, final int requestedCount) {
        final String[] words = text.split(" ");
        if (words.length <= 1 || requestedCount <= 1) {
            return List.of(text);
        }
        final int count = Math.min(requestedCount, words.length);
        final List<String> parts = new ArrayList<>(count);
        int index = 0;
        for (int part = 0; part < count; part++) {
            final int remainingParts = count - part;
            final int remainingWords = words.length - index;
            if (remainingParts == 1) {
                parts.add(String.join(" ", java.util.Arrays.copyOfRange(words, index, words.length)));
                break;
            }
            int take = (int) Math.ceil(remainingWords / (double) remainingParts);
            int end = Math.min(words.length - (remainingParts - 1), index + take);

            // Prefer to end on a sentence boundary in the second half of the group.
            final int minimum = index + Math.max(1, take / 2);
            for (int candidate = end; candidate > minimum; candidate--) {
                if (words[candidate - 1].matches(".*[.!?][\"')\\]”’]?$")) {
                    end = candidate;
                    break;
                }
            }
            final String group = String.join(" ", java.util.Arrays.copyOfRange(words, index, end))
                    .strip();
            if (!group.isEmpty()) {
                parts.add(group);
            }
            index = end;
        }
        return parts;
    }

    static String debugSummary(final List<SubtitleCue> cues) {
        return String.format(Locale.ROOT, "%d cues, %.1f s",
                cues.size(), cues.isEmpty() ? 0 : cues.get(cues.size() - 1).end() - cues.get(0).start());
    }
}
