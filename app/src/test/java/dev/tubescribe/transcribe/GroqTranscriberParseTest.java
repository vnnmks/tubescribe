package dev.tubescribe.transcribe;

import dev.tubescribe.subs.SubtitleCue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic checks of the chunk-response parser against canned Groq
 * {@code verbose_json} bodies: cue ownership (floor partition), the seam
 * bridge, the duplicate rule, the no-segment and no-word fallbacks, and —
 * most importantly — phrase continuity: the parser builds cues from every
 * owned word in one pass, so a word whose phrase continues into the next
 * whisper segment never strands alone in its own subtitle.
 */
public class GroqTranscriberParseTest {

    private final GroqTranscriber transcriber = new GroqTranscriber(List.of());

    private List<SubtitleCue> parse(final String body, final double offset,
                                    final double floor, final double acceptedEnd)
            throws Exception {
        final List<SubtitleCue> cues = new ArrayList<>();
        transcriber.parseCues(body, offset, floor, cues, acceptedEnd, false, "test");
        return cues;
    }

    /** A whisper segment cut mid-sentence leaves one straddling word in the
     * previous segment's window; the continuous word pass keeps the phrase
     * whole instead of emitting a one-word cue. (Reproduces the stranded
     * "the" seen at 00:05 in production logs.) */
    @Test
    public void continuousWordsKeepStraddledPhrasesTogether() throws Exception {
        final String body = """
                {"duration": 10.6,
                 "segments": [
                   {"start": 0.0, "end": 6.0,
                    "text": " Good evening everyone and welcome to"},
                   {"start": 6.0, "end": 10.6,
                    "text": " the following questions of our little show."}],
                 "words": [
                   {"word": "Good", "start": 3.0, "end": 3.3},
                   {"word": "evening", "start": 3.35, "end": 3.7},
                   {"word": "everyone", "start": 3.75, "end": 4.2},
                   {"word": "and", "start": 4.25, "end": 4.4},
                   {"word": "welcome", "start": 4.45, "end": 5.0},
                   {"word": "to", "start": 5.05, "end": 5.5},
                   {"word": "the", "start": 5.5, "end": 6.6},
                   {"word": "following", "start": 6.6, "end": 7.2},
                   {"word": "questions", "start": 7.25, "end": 7.8},
                   {"word": "of", "start": 7.85, "end": 8.0},
                   {"word": "our", "start": 8.05, "end": 8.2},
                   {"word": "little", "start": 8.25, "end": 8.6},
                   {"word": "show.", "start": 8.65, "end": 9.0}]}""";
        final List<SubtitleCue> cues = parse(body, 0.0,
                Double.NEGATIVE_INFINITY, 0.0);
        // All phrase words fit in one cue: no grouping break (size, pause,
        // or duration) fires, so nothing strands "the" before the segment
        // boundary.
        assertEquals(1, cues.size());
        assertEquals(3.0, cues.get(0).start(), 1e-9);
        assertEquals(9.0, cues.get(0).end(), 1e-9);
        assertEquals("Good evening everyone and welcome to the following "
                + "questions of our little show.", cues.get(0).text());
    }

    /** Head-zone segments below the ownership floor are dropped and their
     * phrase text must not appear; a segment ending well past the floor
     * bridges the seam audio from the floor to just before the first owned
     * segment. (Reproduces the seam geometry of overlapping chunks.) */
    @Test
    public void floorDropsHeadAndBridgeCoversSeam() throws Exception {
        final String body = """
                {"duration": 18.0,
                 "segments": [
                   {"start": 5.0, "end": 12.6,
                    "text": " and the seam continues past the cut"},
                   {"start": 13.0, "end": 18.0,
                    "text": " True owned territory begins here."}],
                 "words": [
                   {"word": "and", "start": 5.0, "end": 5.4},
                   {"word": "the", "start": 5.5, "end": 5.7},
                   {"word": "seam", "start": 5.8, "end": 6.2},
                   {"word": "continues", "start": 10.9, "end": 11.4},
                   {"word": "past", "start": 11.45, "end": 11.7},
                   {"word": "the", "start": 11.75, "end": 11.9},
                   {"word": "cut", "start": 11.95, "end": 12.2},
                   {"word": "cut2", "start": 12.3, "end": 12.55},
                   {"word": "True", "start": 13.0, "end": 13.4},
                   {"word": "owned", "start": 13.45, "end": 13.8},
                   {"word": "territory", "start": 13.85, "end": 14.4},
                   {"word": "begins", "start": 14.45, "end": 14.8},
                   {"word": "here.", "start": 14.85, "end": 15.2}]}""";
        final List<SubtitleCue> cues = parse(body, 100.0, 110.0, 0.0);
        assertEquals(2, cues.size());
        // Bridge: only audio at/after the floor, stopped before the owned
        // segment; the dropped head phrase must not appear anywhere.
        for (final SubtitleCue cue : cues) {
            assertFalse("dropped head leaked into cues: " + cue,
                    cue.text().contains("and the seam"));
            assertTrue("cue before the ownership floor: " + cue,
                    cue.start() >= 110.0 - 1e-9);
        }
        assertTrue("bridge words not found: " + cues,
                cues.get(0).text().contains("continues past the cut"));
        assertTrue(cues.get(0).end() <= 113.0 - 0.15 + 1e-9);
        assertEquals("owned segment text: " + cues.get(1),
                "True owned territory begins here.", cues.get(1).text());
        assertEquals(113.0, cues.get(1).start(), 1e-9);
    }

    /** A segment starting just before the previously accepted segment's end
     * is a timing-jitter duplicate and must contribute nothing. */
    @Test
    public void duplicateSegmentAgainstAcceptedTailIsDropped() throws Exception {
        final String body = """
                {"duration": 15.0,
                 "segments": [
                   {"start": 0.0, "end": 5.0,
                    "text": " First part of the audio."},
                   {"start": 4.8, "end": 10.0,
                    "text": " Jittery duplicate of the tail."},
                   {"start": 10.4, "end": 15.0,
                    "text": " The real continuation."}],
                 "words": [
                   {"word": "First", "start": 0.0, "end": 0.4},
                   {"word": "part", "start": 0.45, "end": 0.9},
                   {"word": "of", "start": 0.95, "end": 1.1},
                   {"word": "the", "start": 1.15, "end": 1.3},
                   {"word": "audio.", "start": 1.35, "end": 1.9},
                   {"word": "The", "start": 10.4, "end": 10.7},
                   {"word": "real", "start": 10.75, "end": 11.1},
                   {"word": "continuation.", "start": 11.15, "end": 11.8}]}""";
        final List<SubtitleCue> cues = parse(body, 200.0,
                Double.NEGATIVE_INFINITY, 0.0);
        for (final SubtitleCue cue : cues) {
            assertFalse("duplicate segment text leaked: " + cue,
                    cue.text().contains("Jittery"));
        }
        assertEquals(2, cues.size());
        assertEquals("First part of the audio.", cues.get(0).text());
        assertEquals("The real continuation.", cues.get(1).text());
    }

    /** Without segments the top-level text covers the chunk — but only when
     * the chunk owns its head (floor below the offset). */
    @Test
    public void noSegmentsFallsBackToTopLevelText() throws Exception {
        final String body = """
                {"duration": 12.0, "text": " Whole chunk text without segments."}""";
        final List<SubtitleCue> owned = parse(body, 300.0,
                Double.NEGATIVE_INFINITY, 0.0);
        assertEquals(1, owned.size());
        assertEquals(300.0, owned.get(0).start(), 1e-9);
        assertEquals(312.0, owned.get(0).end(), 1e-9);
        // A chunk whose head is owned by the previous chunk drops the whole
        // fallback: the overlap audio is transcribed there, from a stable
        // file.
        final List<SubtitleCue> dropped = parse(body, 300.0, 305.0, 0.0);
        assertTrue("head fallback must be dropped: " + dropped,
                dropped.isEmpty());
    }

    /** Without a word list, each owned segment splits its text by character
     * share (the old fallback path). */
    @Test
    public void segmentsWithoutWordsSplitByCharacterShare() throws Exception {
        final String body = """
                {"duration": 2.0,
                 "segments": [
                   {"start": 0.0, "end": 2.0, "text": " Hello world."}],
                 "words": []}""";
        final List<SubtitleCue> cues = parse(body, 0.0,
                Double.NEGATIVE_INFINITY, 0.0);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).start(), 1e-9);
        assertEquals(2.0, cues.get(0).end(), 1e-9);
        assertEquals("Hello world.", cues.get(0).text());
    }

    /** A floor-dropped segment that ends only slightly past the floor is
     * jitter of the already-transcribed seam, not a bridge — no cue covers
     * it. */
    @Test
    public void shortHeadExtendIsNotABridge() throws Exception {
        final String body = """
                {"duration": 18.0,
                 "segments": [
                   {"start": 5.0, "end": 12.0,
                    "text": " Short jitter past the floor end"},
                   {"start": 13.0, "end": 18.0,
                    "text": " Owned speech starts here."}],
                 "words": [
                   {"word": "Short", "start": 5.0, "end": 5.3},
                   {"word": "jitter", "start": 5.35, "end": 5.8},
                   {"word": "past", "start": 5.85, "end": 6.1},
                   {"word": "the", "start": 6.15, "end": 6.3},
                   {"word": "floor", "start": 6.35, "end": 6.7},
                   {"word": "end", "start": 6.75, "end": 7.0},
                   {"word": "Owned", "start": 13.0, "end": 13.4},
                   {"word": "speech", "start": 13.45, "end": 13.9},
                   {"word": "starts", "start": 13.95, "end": 14.3},
                   {"word": "here.", "start": 14.35, "end": 14.7}]}""";
        final List<SubtitleCue> cues = parse(body, 100.0, 110.0, 0.0);
        assertEquals(1, cues.size());
        assertEquals("Owned speech starts here.", cues.get(0).text());
        assertEquals(113.0, cues.get(0).start(), 1e-9);
    }
}
