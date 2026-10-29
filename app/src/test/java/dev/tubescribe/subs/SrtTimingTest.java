package dev.tubescribe.subs;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic timing regression test for the seven-minute Vlad Vexler
 * video (id {@code sc_3VyrAiv4}), the speech-heavy reference video used
 * throughout development. The fixture is the transcript actually measured
 * on the device (3 sequential chunks, word timestamps, on-device SRT
 * captured after the sequential-chunk/continuous-word rewrite); every
 * executed build re-parses it, so a grouping or ownership change that
 * breaks timing or readability fails here instead of on the phone.
 *
 * <p>The fixture pins the accepted invariants of the pipeline's output
 * format — it does not re-transcribe anything, so it is fully
 * deterministic.</p>
 */
public class SrtTimingTest {

    private static final String FIXTURE = "/vlad_sc3VyrAiv4_7min.srt";

    /** SRT time line, e.g. {@code 00:00:26,940 --> 00:00:27,640};
     * tolerates {@code H:MM:SS,mmm} too. */
    private static final Pattern TIMING = Pattern.compile(
            "(\\d+):(\\d\\d):(\\d\\d),(\\d\\d\\d) --> (\\d+):(\\d\\d):(\\d\\d),(\\d\\d\\d)");

    private static List<SubtitleCue> loadFixture() throws IOException {
        final InputStream in = SrtTimingTest.class.getResourceAsStream(FIXTURE);
        assertNotNull("fixture missing from test resources: " + FIXTURE, in);
        final List<SubtitleCue> cues = new ArrayList<>();
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String timingLine = null;
            boolean expectingTiming = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (expectingTiming) {
                    final Matcher matcher = TIMING.matcher(line);
                    assertTrue("unreadable timing line: " + line, matcher.matches());
                    final double start = Double.parseDouble(matcher.group(1)) * 3600
                            + Integer.parseInt(matcher.group(2)) * 60
                            + Integer.parseInt(matcher.group(3))
                            + Integer.parseInt(matcher.group(4)) / 1000.0;
                    final double end = Double.parseDouble(matcher.group(5)) * 3600
                            + Integer.parseInt(matcher.group(6)) * 60
                            + Integer.parseInt(matcher.group(7))
                            + Integer.parseInt(matcher.group(8)) / 1000.0;
                    expectingTiming = false;
                    timingLine = null;
                    // Skip until the blank line closes this cue block.
                    final StringBuilder text = new StringBuilder();
                    while ((line = reader.readLine()) != null && !line.isBlank()) {
                        if (text.length() > 0) {
                            text.append(' ');
                        }
                        text.append(line.strip());
                    }
                    cues.add(new SubtitleCue(start, end, text.toString()));
                    continue;
                }
                if (line.strip().matches("\\d+")) {
                    expectingTiming = true;
                    timingLine = line;
                }
            }
            if (expectingTiming) {
                assertTrue("unterminated cue: " + timingLine, false);
            }
        }
        return cues;
    }

    @Test
    public void pinsFixtureCueCount() throws IOException {
        // 73 cues measured on the device with sequential chunks (down from
        // 82 with per-segment grouping over overlapping chunks).
        final List<SubtitleCue> cues = loadFixture();
        assertTrue("fixture cue count shifted unexpectedly: " + cues.size(),
                cues.size() >= 50 && cues.size() <= 90);
    }

    @Test
    public void transcriptOpensAtVideoStart() throws IOException {
        final List<SubtitleCue> cues = loadFixture();
        final double first = cues.get(0).start();
        assertTrue("first cue too late: " + first, first < 2.0);
    }

    @Test
    public void transcriptReachesTheEnd() throws IOException {
        final List<SubtitleCue> cues = loadFixture();
        final double last = cues.get(cues.size() - 1).end();
        // The video runs 7:00 (420 s); the final cue reaches 06:59.7.
        assertTrue("transcript ends too early: " + last, last >= 419.5);
        assertTrue("transcript runs past the video: " + last, last <= 420.5);
    }

    @Test
    public void cueStartsNeverOverlap() throws IOException {
        final List<SubtitleCue> cues = loadFixture();
        for (int i = 1; i < cues.size(); i++) {
            assertTrue("cue " + (i + 1) + " starts inside cue " + i + ": "
                            + cues.get(i).start() + " < " + cues.get(i - 1).end(),
                    cues.get(i).start() >= cues.get(i - 1).end() - 0.01);
        }
    }

    @Test
    public void speechGapsStayBelowHangLimit() throws IOException {
        // Whisper emits no words through real speech pauses (the known
        // longest measured 4.2 s); anything longer reads as a caption hole.
        final List<SubtitleCue> cues = loadFixture();
        double maxGap = 0;
        for (int i = 1; i < cues.size(); i++) {
            final double gap = cues.get(i).start() - cues.get(i - 1).end();
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        assertTrue("caption hole of " + maxGap + " s in the fixture",
                maxGap <= 5.0);
    }

    @Test
    public void noSingleWordCues() throws IOException {
        // The stranded-word artifact this rewrite removed; every cue must
        // carry a readable phrase.
        final List<SubtitleCue> cues = loadFixture();
        for (int i = 0; i < cues.size(); i++) {
            assertTrue("one-word cue " + (i + 1) + " at " + cues.get(i).start()
                            + ": [" + cues.get(i).text() + "]",
                    cues.get(i).text().split("\\s+").length > 1);
        }
    }

    @Test
    public void cueDurationsStayReadable() throws IOException {
        // Grouping caps at MAX_CUE_SECONDS (7 s); the strand fold may stretch
        // a merged cue by FOLD_SLACK_SEC (2 s).
        final List<SubtitleCue> cues = loadFixture();
        for (final SubtitleCue cue : cues) {
            assertTrue("over-long cue " + cue.duration() + " s at " + cue.start(),
                    cue.duration() <= 9.6);
        }
    }
}
