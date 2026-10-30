package dev.tubescribe.subs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a transcript's word timestamps into readable cues.
 *
 * <p>Cues are built in two stages. First the word stream is cut at natural
 * pauses (and at chunk seams, because each chunk is passed in separately).
 * Each pause-free span that does not fit the size/duration limits is then
 * split by a small dynamic program that balances cue length while preferring
 * punctuation boundaries and avoiding breaks around function words.</p>
 *
 * <p>When word timestamps are unavailable, each word receives a synthetic
 * time proportional to its character count and the same splitter is used, so
 * there is only one set of cue rules.</p>
 */
public final class CueBuilder {
    private static final int MAX_CHARS = 84;
    private static final double MAX_CUE_SECONDS = 7.0;
    private static final double MIN_CUE_SECONDS = 0.55;
    private static final Pattern NON_SPEECH = Pattern.compile(
            "^[\\[\\(]\\s*(music|applause|laughter|blank_audio|silence|noise|inaudible)[^\\]\\)]*[\\]\\)]$",
            Pattern.CASE_INSENSITIVE);

    /** Break cue grouping when the next word waits longer than this. */
    private static final double PAUSE_BREAK_SEC = 0.6;
    /** Short cues are held up to this long so fast phrases do not flash;
     * never past the next cue's start. */
    private static final double MIN_HOLD_SEC = 1.6;

    // --- Balanced-split scoring -------------------------------------------
    // Cue length dominates; the other terms are tie-breakers. Units are
    // "characters squared", so a bonus of 120 is worth roughly an 11-char
    // imbalance.
    private static final double SENTENCE_BONUS = 120.0;
    private static final double CLAUSE_BONUS = 40.0;
    private static final double GAP_BONUS = 80.0;
    private static final double STOPWORD_PENALTY = 80.0;
    private static final double CONJUNCTION_PENALTY = 80.0;
    private static final double ONE_WORD_PENALTY = 5_000.0;
    private static final double READING_CHARS_PER_SEC = 20.0;
    private static final double READING_PENALTY = 4.0;

    // Display line breaks are scored in plain characters, not characters
    // squared, and the preferences are small on purpose: they choose between
    // near-even boundaries but must never trade away a visibly lopsided pair
    // of lines. A bad ending (article, "to", conjunction) is the only strong
    // penalty, because it strands the start of the next phrase.
    private static final double LINE_SENTENCE_BONUS = 4.0;
    private static final double LINE_CLAUSE_BONUS = 2.0;
    private static final double LINE_STOPWORD_PENALTY = 1.0;
    private static final double LINE_CONJUNCTION_PENALTY = 2.0;
    private static final double LINE_BAD_END_PENALTY = 8.0;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "to", "of", "in", "on", "at", "for", "from",
            "by", "with", "as", "is", "are", "was", "were", "be", "been",
            "being", "that", "this", "these", "those", "it", "its", "his",
            "her", "their", "our", "your", "my", "into", "over", "under",
            "between", "through", "during", "without", "within", "about",
            "after", "before", "against", "among", "around", "upon", "via");
    private static final Set<String> CONJUNCTIONS = Set.of(
            "and", "but", "that", "which", "who", "whom", "whose", "because",
            "although", "though", "while", "whereas", "or", "nor", "yet",
            "so", "if", "when", "where", "as");
    /** Words that must not end a display line: they leave the next line
     * starting with a determiner or the rest of a phrase. Prepositions such
     * as "in" and "of" are acceptable line endings for subtitles and are not
     * listed here. */
    private static final Set<String> BAD_LINE_ENDINGS = Set.of(
            "the", "a", "an", "this", "that", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            "to", "and", "but", "or", "nor", "yet", "so", "because",
            "although", "though", "while", "whereas", "if", "when",
            "where", "which", "who", "whom", "whose", "as");

    private static final Pattern SENTENCE_END = Pattern.compile(
            ".*[.!?\u2026][\"')\\]\u201d\u2019]?$");
    private static final Pattern CLAUSE_END = Pattern.compile(
            ".*[,;:][\"')\\]\u201d\u2019]?$");

    private CueBuilder() {
    }

    /** One timed word from whisper's word-level timestamps. */
    public record WordSpan(String word, double start, double end) {
    }

    /**
     * Builds cues from whisper's word-level timestamps.
     *
     * <p>Each call is one chunk's word stream, so call boundaries are hard
     * seams: a phrase is never grouped across a chunk boundary.</p>
     */
    public static List<SubtitleCue> fromWords(final List<WordSpan> words) {
        return fromWords(words, 0);
    }

    private static List<SubtitleCue> fromWords(final List<WordSpan> words,
                                               final double minCueSeconds) {
        final List<Token> tokens = tokenize(words);
        if (tokens.isEmpty()) {
            return List.of();
        }
        final List<SubtitleCue> built = new ArrayList<>();
        int spanStart = 0;
        for (int i = 1; i < tokens.size(); i++) {
            if (tokens.get(i).start() - tokens.get(i - 1).end() >= PAUSE_BREAK_SEC) {
                splitSpan(tokens, spanStart, i, minCueSeconds, built);
                spanStart = i;
            }
        }
        splitSpan(tokens, spanStart, tokens.size(), minCueSeconds, built);
        return applyMinHold(built);
    }

    /**
     * Fallback for responses without word timestamps: give every word a
     * synthetic time proportional to its character length, then run the same
     * balanced splitter. The minimum-duration rule replaces the old
     * character-share floor; the punctuation preference replaces "back up to
     * the nearest sentence end".
     */
    public static List<SubtitleCue> fromSegment(final double start, final double duration,
                                                final String rawText) {
        final String text = clean(rawText);
        if (text.isEmpty() || duration <= 0.05) {
            return List.of();
        }
        final String[] words = text.split(" ");
        if (words.length == 0) {
            return List.of();
        }
        int totalChars = 0;
        for (final String word : words) {
            totalChars += word.length();
        }
        if (totalChars == 0) {
            return List.of();
        }
        final List<WordSpan> synthetic = new ArrayList<>(words.length);
        double cursor = start;
        int consumed = 0;
        for (int i = 0; i < words.length; i++) {
            consumed += words[i].length();
            final double end = i == words.length - 1
                    ? start + duration
                    : start + duration * (consumed / (double) totalChars);
            final double wordEnd = Math.max(cursor, end);
            synthetic.add(new WordSpan(words[i], cursor, wordEnd));
            cursor = wordEnd;
        }
        return fromWords(synthetic, MIN_CUE_SECONDS);
    }

    /** A word prepared for length math: {@code text} is trimmed, while
     * {@code raw} still carries whisper's spacing for exact cue text. */
    private record Token(String raw, String text, double start, double end,
                         int sepBefore) {
    }

    private static List<Token> tokenize(final List<WordSpan> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        final List<Token> tokens = new ArrayList<>(words.size());
        for (final WordSpan word : words) {
            if (word == null || word.word() == null || word.word().isBlank()) {
                continue;
            }
            final String raw = word.word();
            final String text = raw.strip();
            if (text.isEmpty()) {
                continue;
            }
            int sepBefore = 0;
            if (!tokens.isEmpty()) {
                final char first = raw.charAt(0);
                final boolean leadingSpace = Character.isWhitespace(first);
                final boolean glued = leadingSpace
                        || first == ',' || first == '.' || first == '!'
                        || first == '?' || first == ';' || first == ':'
                        || first == '\u2026' || first == ')'
                        || first == '\'' || first == '\u2019';
                sepBefore = leadingSpace || !glued ? 1 : 0;
            }
            tokens.add(new Token(raw, text, word.start(), word.end(), sepBefore));
        }
        return tokens;
    }

    /** Splits one pause-free span, or emits it whole when it already fits. */
    private static void splitSpan(final List<Token> tokens, final int from, final int to,
                                  final double minCueSeconds,
                                  final List<SubtitleCue> out) {
        if (from >= to) {
            return;
        }
        final int chars = rangeChars(tokens, from, to);
        final double duration = Math.max(0,
                tokens.get(to - 1).end() - tokens.get(from).start());
        if (chars <= MAX_CHARS && duration <= MAX_CUE_SECONDS) {
            addCue(tokens, from, to, out);
            return;
        }

        int count = Math.max(
                (int) Math.ceil(chars / (double) MAX_CHARS),
                (int) Math.ceil(duration / MAX_CUE_SECONDS));
        count = Math.max(1, count);
        final double target = chars / (double) count;
        List<int[]> parts = dp(tokens, from, to, target, minCueSeconds);
        if (parts == null && minCueSeconds > 0) {
            // A short, dense segment cannot satisfy both the character cap
            // and the duration floor. Readability wins: relax the floor.
            parts = dp(tokens, from, to, target, 0);
        }
        if (parts == null) {
            // No split exists even with one-word cues (e.g. a single token
            // longer than the character cap). Keep every word, unsplit.
            for (int i = from; i < to; i++) {
                addCue(tokens, i, i + 1, out);
            }
            return;
        }
        for (final int[] part : parts) {
            addCue(tokens, part[0], part[1], out);
        }
    }

    /**
     * Balanced partition of one span. Multi-word cues may not exceed
     * {@link #MAX_CHARS} or {@link #MAX_CUE_SECONDS}; a single token that is
     * longer than the character cap is allowed as its own cue because there
     * is no word boundary to split it at. The cost pulls each cue toward the
     * span's average length and adjusts for punctuation, pauses, function
     * words, one-word cues, and reading speed.
     */
    private static List<int[]> dp(final List<Token> tokens, final int from, final int to,
                                  final double target, final double minCueSeconds) {
        final int n = to - from;
        final double[] best = new double[n + 1];
        final int[] prev = new int[n + 1];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[0] = 0;

        for (int j = 1; j <= n; j++) {
            final int cueEndIndex = from + j - 1;
            final double cueEnd = tokens.get(cueEndIndex).end();
            int len = 0;
            for (int i = j - 1; i >= 0; i--) {
                final int first = from + i;
                if (i == j - 1) {
                    len = tokens.get(first).text().length();
                } else {
                    len += tokens.get(first).text().length()
                            + tokens.get(first + 1).sepBefore();
                }
                if (len > MAX_CHARS && i != j - 1) {
                    break;
                }
                final double cueStart = tokens.get(first).start();
                final double dur = cueEnd - cueStart;
                if (dur > MAX_CUE_SECONDS + 1e-9 && i != j - 1) {
                    continue;
                }
                if (minCueSeconds > 0 && dur + 1e-9 < minCueSeconds) {
                    continue;
                }
                if (best[i] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                final double cost = best[i] + cueCost(tokens, first, from + j, to,
                        len, dur, target);
                if (cost < best[j]) {
                    best[j] = cost;
                    prev[j] = i;
                }
            }
        }

        if (best[n] == Double.POSITIVE_INFINITY) {
            return null;
        }
        final List<int[]> parts = new ArrayList<>();
        int j = n;
        while (j > 0) {
            final int i = prev[j];
            parts.add(0, new int[]{from + i, from + j});
            j = i;
        }
        return parts;
    }

    private static double cueCost(final List<Token> tokens, final int cueStart,
                                  final int cueEnd, final int spanEnd, final int len,
                                  final double duration, final double target) {
        final double imbalance = len - target;
        double cost = imbalance * imbalance;
        if (cueEnd - cueStart < 2) {
            cost += ONE_WORD_PENALTY;
        }
        if (cueEnd < spanEnd) {
            final Token last = tokens.get(cueEnd - 1);
            final Token next = tokens.get(cueEnd);
            final double gap = Math.max(0, next.start() - last.end());
            cost += breakAdjustment(last.text(), next.text(), gap);
        }
        // A zero or near-zero cue would make the rate infinite; cap the
        // excess at 30 chars/s so one pathological cue cannot swamp the DP.
        final double cps = len / Math.max(duration, 0.05);
        if (cps > READING_CHARS_PER_SEC) {
            final double over = Math.min(cps - READING_CHARS_PER_SEC, 30.0);
            cost += READING_PENALTY * over * over;
        }
        return cost;
    }

    /** Negative for a good break, positive for a bad one. */
    private static double breakAdjustment(final String lastWord, final String nextWord,
                                          final double gap) {
        double cost = 0;
        if (SENTENCE_END.matcher(lastWord).matches()) {
            cost -= SENTENCE_BONUS;
        } else if (CLAUSE_END.matcher(lastWord).matches()) {
            cost -= CLAUSE_BONUS;
        }
        final String last = normalizeWord(lastWord);
        final String next = normalizeWord(nextWord);
        if (STOPWORDS.contains(last) || CONJUNCTIONS.contains(last)) {
            cost += STOPWORD_PENALTY;
        }
        if (CONJUNCTIONS.contains(next)) {
            cost += CONJUNCTION_PENALTY;
        }
        if (gap > 0) {
            cost -= GAP_BONUS * Math.min(gap, PAUSE_BREAK_SEC) / PAUSE_BREAK_SEC;
        }
        return cost;
    }

    private static String normalizeWord(final String word) {
        int start = 0;
        int end = word.length();
        while (start < end && !Character.isLetterOrDigit(word.charAt(start))
                && word.charAt(start) != '\'') {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(word.charAt(end - 1))
                && word.charAt(end - 1) != '\'') {
            end--;
        }
        return word.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static int rangeChars(final List<Token> tokens, final int from, final int to) {
        int chars = 0;
        for (int i = from; i < to; i++) {
            chars += tokens.get(i).text().length();
            if (i > from) {
                chars += tokens.get(i).sepBefore();
            }
        }
        return chars;
    }

    private static void addCue(final List<Token> tokens, final int from, final int to,
                               final List<SubtitleCue> out) {
        final String text = cueText(tokens, from, to);
        if (text.isEmpty()) {
            return;
        }
        final double start = tokens.get(from).start();
        final double end = tokens.get(to - 1).end();
        if (end > start) {
            out.add(new SubtitleCue(start, end, text));
        }
    }

    private static String cueText(final List<Token> tokens, final int from, final int to) {
        final StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            appendWord(text, tokens.get(i).raw());
        }
        return clean(text.toString());
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
                    || first == ';' || first == ':' || first == '\u2026' || first == ')'
                    || first == '\'' || first == '\u2019';
            if (!glued) {
                text.append(' ');
            }
        }
        text.append(raw);
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
        text = text.replaceAll("^[-\u2013\u2014]+\\s*", "");
        text = text.replaceAll("^[\u266a\u266b\\s]+", "").strip();
        if (text.isEmpty() || text.equals("\u266a") || NON_SPEECH.matcher(text).matches()) {
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

    /** Holds every cue shorter than {@link #MIN_CUE_SECONDS}, never past the
     * next cue's start. */
    private static List<SubtitleCue> applyMinHold(final List<SubtitleCue> cues) {
        for (int i = 0; i < cues.size(); i++) {
            final SubtitleCue cue = cues.get(i);
            if (cue.duration() >= MIN_CUE_SECONDS) {
                continue;
            }
            final double limit = i + 1 < cues.size()
                    ? Math.min(cue.start() + MIN_HOLD_SEC, cues.get(i + 1).start() - 0.02)
                    : cue.start() + MIN_HOLD_SEC;
            if (limit > cue.end()) {
                cues.set(i, new SubtitleCue(cue.start(), limit, cue.text()));
            }
        }
        return cues;
    }

    /**
     * Splits one cue into two display lines at the word boundary with the
     * best balance, using punctuation/function-word preferences only to
     * choose between near-even boundaries. The returned text contains
     * exactly one {@code \n}. Callers use this only when the cue does not fit
     * one line.
     */
    public static String balanceLines(final String rawText) {
        final String text = rawText == null ? ""
                : rawText.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "";
        }
        final String[] words = text.split(" ");
        if (words.length < 2) {
            return text;
        }
        int best = 1;
        double bestScore = Double.POSITIVE_INFINITY;
        int bestImbalance = Integer.MAX_VALUE;
        for (int i = 1; i < words.length; i++) {
            int left = 0;
            int right = 0;
            for (int k = 0; k < i; k++) {
                if (k > 0) {
                    left++;
                }
                left += words[k].length();
            }
            for (int k = i; k < words.length; k++) {
                if (k > i) {
                    right++;
                }
                right += words[k].length();
            }
            final int imbalance = Math.abs(left - right);
            final double score = imbalance
                    + lineBreakAdjustment(words[i - 1], words[i]);
            if (score < bestScore - 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9 && imbalance < bestImbalance)) {
                bestScore = score;
                bestImbalance = imbalance;
                best = i;
            }
        }
        return String.join(" ", Arrays.copyOfRange(words, 0, best))
                + '\n'
                + String.join(" ", Arrays.copyOfRange(words, best, words.length));
    }

    /**
     * Small, line-specific preferences. These intentionally weigh far less
     * than the cue-splitting terms so that balance wins: a candidate can only
     * be a few characters less even before the preference makes it worse.
     */
    private static double lineBreakAdjustment(final String lastWord,
                                              final String nextWord) {
        double cost = 0;
        if (SENTENCE_END.matcher(lastWord).matches()) {
            cost -= LINE_SENTENCE_BONUS;
        } else if (CLAUSE_END.matcher(lastWord).matches()) {
            cost -= LINE_CLAUSE_BONUS;
        }
        final String last = normalizeWord(lastWord);
        if (BAD_LINE_ENDINGS.contains(last)) {
            cost += LINE_BAD_END_PENALTY;
        } else if (STOPWORDS.contains(last)) {
            cost += LINE_STOPWORD_PENALTY;
        }
        if (CONJUNCTIONS.contains(normalizeWord(nextWord))) {
            cost += LINE_CONJUNCTION_PENALTY;
        }
        return cost;
    }

    static String debugSummary(final List<SubtitleCue> cues) {
        return String.format(Locale.ROOT, "%d cues, %.1f s",
                cues.size(), cues.isEmpty() ? 0 : cues.get(cues.size() - 1).end() - cues.get(0).start());
    }
}
