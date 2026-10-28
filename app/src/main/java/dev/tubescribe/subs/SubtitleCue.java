package dev.tubescribe.subs;

import java.util.Comparator;

/** One timed subtitle line. */
public record SubtitleCue(double start, double end, String text) implements Comparable<SubtitleCue> {
    public SubtitleCue {
        start = Math.max(0, start);
        end = Math.max(start, end);
        text = text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    public double duration() {
        return end - start;
    }

    @Override
    public int compareTo(final SubtitleCue other) {
        return Double.compare(start, other.start);
    }

    public static Comparator<SubtitleCue> byStart() {
        return Comparator.comparingDouble(SubtitleCue::start);
    }
}
