package dev.tubescribe.subs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic checks for word-timestamp cue building: grouping by size
 * and pauses, the fold that keeps stranded single words from flashing by
 * as their own subtitle, and the short-cue hold.
 */
public class CueBuilderTest {

    private static CueBuilder.WordSpan w(final String word, final double start,
                                         final double end) {
        return new CueBuilder.WordSpan(word, start, end);
    }

    @Test
    public void pauseBreaksGroupsAndStillFoldsStrandedWords() {
        // A word with long pauses on both sides would come out alone; the
        // fold pulls it back into the phrase it interrupts.
        final List<CueBuilder.WordSpan> words = List.of(
                w("Let's", 20.5, 20.8),
                w("generate", 20.85, 21.3),
                w("slop", 27.0, 27.6),
                w("Word", 28.6, 28.9),
                w("like", 28.95, 29.2),
                w("here.", 29.25, 29.6));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        assertEquals(20.5, cues.get(0).start(), 1e-9);
        assertEquals(27.6, cues.get(0).end(), 1e-9);
        assertEquals("Let's generate slop", cues.get(0).text());
        assertEquals("Word like here.", cues.get(1).text());
    }

    @Test
    public void longStreamsSplitBySizeAtWordBoundaries() {
        final List<CueBuilder.WordSpan> words = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < 12; i++) {
            final String word = "abcdefghij";
            words.add(w(word, t, t + 0.3));
            t += 0.4;
        }
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertTrue("expected a size split, got " + cues.size(), cues.size() >= 2);
        // One continuous stream: cues never overlap, and any gap between
        // them is word-spacing scale, not silence.
        for (int i = 1; i < cues.size(); i++) {
            assertTrue("cue starts before its predecessor ends: " + cues.get(i),
                    cues.get(i).start() >= cues.get(i - 1).end() - 1e-9);
            assertTrue("unexpected gap between cues: " + cues.get(i).start(),
                    cues.get(i).start() - cues.get(i - 1).end() <= 0.6);
            assertTrue("cue exceeds size slack: " + cues.get(i).text(),
                    cues.get(i).text().length() <= 84 + 16);
        }
    }

    @Test
    public void durationFlushTailWordFoldsBackward() {
        // A group ending exactly at MAX_CUE_SECONDS leaves the next word to
        // stand alone; it folds back into the flushed cue.
        final List<CueBuilder.WordSpan> words = List.of(
                w("one", 0.0, 0.3),
                w("two", 0.4, 6.9),
                w("three", 7.0, 7.4));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).start(), 1e-9);
        assertEquals(7.4, cues.get(0).end(), 1e-9);
        assertEquals("one two three", cues.get(0).text());
    }

    @Test
    public void foldGuardRejectsOversizeMerge() {
        // Two 50-char words flush together at 101 chars; the trailing
        // 50-char word would push the cue to 152 chars — far past readable
        // length — so the guard leaves it standing (a rare 1-token cue
        // rather than a bloated one).
        final String longWord = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final List<CueBuilder.WordSpan> words = List.of(
                w(longWord, 0.0, 0.3),
                w(longWord, 0.4, 0.7),
                w(longWord, 0.8, 1.1));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        assertEquals(101, cues.get(0).text().length());
        assertEquals(1, cues.get(1).text().split("\\s+").length);
        assertEquals(longWord, cues.get(1).text());
    }

    @Test
    public void firstCueLoneWordForwardsIntoNext() {
        final List<CueBuilder.WordSpan> words = List.of(
                w("Bonjour", 0.0, 0.3),
                w("world", 1.5, 1.9),
                w("again.", 2.0, 2.4));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).start(), 1e-9);
        assertEquals(2.4, cues.get(0).end(), 1e-9);
        assertEquals("Bonjour world again.", cues.get(0).text());
    }

    @Test
    public void shortCueHoldsUntilNextStarts() {
        final List<CueBuilder.WordSpan> words = List.of(
                w("one", 0.0, 0.2),
                w("two", 0.25, 0.4),
                w("much", 3.0, 3.3),
                w("later", 3.35, 3.6));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        // Short cue holds up to MIN_HOLD_SEC but never into the next cue.
        assertTrue(cues.get(0).end() >= 1.5);
        assertTrue(cues.get(0).end() <= cues.get(1).start());
    }
}
