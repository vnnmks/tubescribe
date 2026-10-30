package dev.tubescribe.subs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic checks for the balanced cue splitter: pause spans, hard
 * size/duration limits, punctuation-aware balancing, the isolated-word rule,
 * and the short-cue hold.
 */
public class CueBuilderTest {

    private static CueBuilder.WordSpan w(final String word, final double start,
                                         final double end) {
        return new CueBuilder.WordSpan(word, start, end);
    }

    private static List<CueBuilder.WordSpan> stream(final int count, final String word,
                                                    final double wordSeconds,
                                                    final double gapSeconds) {
        final List<CueBuilder.WordSpan> words = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < count; i++) {
            words.add(w(word, t, t + wordSeconds));
            t += wordSeconds + gapSeconds;
        }
        return words;
    }

    @Test
    public void pauseIsolatedWordsStayOwnCues() {
        // "slop" has a real pause on both sides. Folding it backward would
        // keep the previous text on screen through that silence; the
        // balanced splitter leaves it alone and lets the hold cover it.
        final List<CueBuilder.WordSpan> words = List.of(
                w("Let's", 20.5, 20.8),
                w("generate", 20.85, 21.3),
                w("slop", 27.0, 27.6),
                w("Word", 28.6, 28.9),
                w("like", 28.95, 29.2),
                w("here.", 29.25, 29.6));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(3, cues.size());
        assertEquals("Let's generate", cues.get(0).text());
        assertEquals(20.5, cues.get(0).start(), 1e-9);
        assertEquals(21.3, cues.get(0).end(), 1e-9);
        assertEquals("slop", cues.get(1).text());
        assertEquals(27.0, cues.get(1).start(), 1e-9);
        assertEquals(27.6, cues.get(1).end(), 1e-9);
        assertEquals("Word like here.", cues.get(2).text());
    }

    @Test
    public void longStreamsSplitAtBalancedWordBoundaries() {
        final List<CueBuilder.WordSpan> words = stream(12, "abcdefghij", 0.3, 0.1);
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        // Six words per cue is the only balanced 84-char split.
        assertEquals(65, cues.get(0).text().length());
        assertEquals(65, cues.get(1).text().length());
        for (final SubtitleCue cue : cues) {
            assertTrue("cue exceeds the hard character cap: " + cue.text().length(),
                    cue.text().length() <= 84);
        }
        for (int i = 1; i < cues.size(); i++) {
            assertTrue("cue starts before its predecessor ends: " + cues.get(i),
                    cues.get(i).start() >= cues.get(i - 1).end() - 1e-9);
        }
    }

    @Test
    public void durationLimitSplitsWithoutOvershoot() {
        // A 7.9 s pause-free span must split; the old size flush could leave
        // a trailing one-word cue, while the DP balances the two cues.
        final List<CueBuilder.WordSpan> words = List.of(
                w("one", 0.0, 0.3),
                w("two", 0.4, 6.9),
                w("three", 7.0, 7.4),
                w("four", 7.45, 7.9));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        assertEquals("one two", cues.get(0).text());
        assertEquals("three four", cues.get(1).text());
        for (final SubtitleCue cue : cues) {
            assertTrue("cue exceeds the hard duration cap: " + cue.duration(),
                    cue.duration() <= 7.0 + 1e-9);
        }
    }

    @Test
    public void oversizeWordsRelaxTheTwoWordRule() {
        // Three 50-char words cannot pair up inside 84 chars, so each word
        // becomes its own cue instead of an over-cap merged cue.
        final String longWord = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final List<CueBuilder.WordSpan> words = List.of(
                w(longWord, 0.0, 0.3),
                w(longWord, 0.4, 0.7),
                w(longWord, 0.8, 1.1));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(3, cues.size());
        for (final SubtitleCue cue : cues) {
            assertEquals(longWord, cue.text());
        }
    }

    @Test
    public void firstLoneWordStaysOwnCue() {
        final List<CueBuilder.WordSpan> words = List.of(
                w("Bonjour", 0.0, 0.3),
                w("world", 1.5, 1.9),
                w("again.", 2.0, 2.4));
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        assertEquals("Bonjour", cues.get(0).text());
        assertEquals(0.0, cues.get(0).start(), 1e-9);
        // Held briefly, but never up to the next cue's start.
        assertEquals(1.48, cues.get(0).end(), 1e-9);
        assertEquals("world again.", cues.get(1).text());
        assertEquals(1.5, cues.get(1).start(), 1e-9);
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
        assertEquals(1.6, cues.get(0).end(), 1e-9);
        assertEquals(3.0, cues.get(1).start(), 1e-9);
    }

    @Test
    public void balancedSplitPrefersSentenceEnd() {
        // Twenty 5-char words with a period on the tenth. Both halves are
        // exactly 59 chars, so the sentence break is the clear optimum.
        final List<CueBuilder.WordSpan> words = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < 20; i++) {
            final String word = String.valueOf((char) ('a' + i)).repeat(5)
                    + (i == 9 ? "." : "");
            words.add(w(word, t, t + 0.3));
            t += 0.35;
        }
        final List<SubtitleCue> cues = CueBuilder.fromWords(words);
        assertEquals(2, cues.size());
        assertTrue("sentence break lost: " + cues.get(0).text(),
                cues.get(0).text().endsWith("jjjjj."));
        assertTrue("second half starts too early: " + cues.get(1).text(),
                cues.get(1).text().startsWith("kkkkk"));
    }

    @Test
    public void fallbackUsesSyntheticWordTimes() {
        final String text = "This is a fairly long fallback sentence that should be "
                + "divided into balanced and readable subtitle cues without word timestamps.";
        final List<SubtitleCue> cues = CueBuilder.fromSegment(10.0, 6.0, text);
        assertTrue("expected a split, got " + cues.size(), cues.size() >= 2);
        assertEquals(10.0, cues.get(0).start(), 1e-9);
        assertEquals(16.0, cues.get(cues.size() - 1).end(), 1e-9);
        final StringBuilder joined = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            final SubtitleCue cue = cues.get(i);
            if (i > 0) {
                assertEquals("fallback cues must be contiguous",
                        cues.get(i - 1).end(), cue.start(), 1e-9);
                joined.append(' ');
            }
            joined.append(cue.text());
            assertTrue("fallback cue below the minimum duration",
                    cue.duration() >= 0.55 - 1e-9);
        }
        assertEquals(text, joined.toString());
    }

    @Test
    public void fallbackRelaxesDurationFloorWhenTextCannotFit() {
        // 119 chars in 1 s: two cues are needed for the character cap, but
        // each would be 0.5 s. The character cap wins.
        final String text = "aaaaa bbbbb ccccc ddddd eeeee fffff ggggg hhhhh iiiii jjjjj "
                + "kkkkk lllll mmmmm nnnnn ooooo ppppp qqqqq rrrrr sssss ttttt";
        final List<SubtitleCue> cues = CueBuilder.fromSegment(0.0, 1.0, text);
        assertEquals(2, cues.size());
        assertEquals(0.5, cues.get(0).end(), 1e-9);
        assertEquals(0.5, cues.get(1).start(), 1e-9);
    }

    @Test
    public void balanceLinesUsesTheEvenestWordBoundary() {
        assertEquals("This is a fairly long subtitle\nline that should break evenly",
                CueBuilder.balanceLines(
                        "This is a fairly long subtitle line that should break evenly"));
    }

    @Test
    public void balanceLinesKeepsLinesEvenInsteadOfFollowingClauseBonus() {
        // Splitting after the comma would be syntactically tidy but leaves
        // 12 characters between the lines; the near-even break after
        // "hunting" wins instead.
        assertEquals("Ukraine initiated Operation Vivaldi, hunting\n"
                        + "for places to counterattack and relieve",
                CueBuilder.balanceLines(
                        "Ukraine initiated Operation Vivaldi, hunting for places "
                                + "to counterattack and relieve"));
        // The old stopword penalty produced 44/53 here. Ending a line with
        // "in" is perfectly acceptable and gives 47/50.
        assertEquals("That appears to be part of what is happening in\n"
                        + "the bottom half of this territorial exchange data.",
                CueBuilder.balanceLines(
                        "That appears to be part of what is happening in the "
                                + "bottom half of this territorial exchange data."));
    }

    @Test
    public void balanceLinesStillAvoidsDanglingArticlesAndInfinitives() {
        // "the" at the end of a line is worth taking a slightly less even
        // break; the same applies to an infinitive split after "to".
        assertEquals("one two\nthe three four",
                CueBuilder.balanceLines("one two the three four"));
        assertEquals("did not know where they needed to go,\n"
                        + "and so Ukraine marched eastward.",
                CueBuilder.balanceLines(
                        "did not know where they needed to go, and so Ukraine "
                                + "marched eastward."));
    }

    @Test
    public void balanceLinesMayStartALineWithAConjunctionToStayEven() {
        assertEquals("alpha beta gamma\nand delta epsilon",
                CueBuilder.balanceLines("alpha beta gamma and delta epsilon"));
    }
}
