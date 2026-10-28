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

    static String clean(final String rawText) {
        if (rawText == null) {
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
