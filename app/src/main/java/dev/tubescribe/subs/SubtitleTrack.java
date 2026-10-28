package dev.tubescribe.subs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An ordered set of subtitle cues plus SRT/VTT import and export. */
public final class SubtitleTrack {
    private final List<SubtitleCue> cues;

    public SubtitleTrack(final List<SubtitleCue> cues) {
        final List<SubtitleCue> copy = new ArrayList<>(cues);
        copy.removeIf(cue -> cue.text().isBlank());
        copy.sort(SubtitleCue.byStart());
        this.cues = List.copyOf(copy);
    }

    public static SubtitleTrack empty() {
        return new SubtitleTrack(List.of());
    }

    public List<SubtitleCue> cues() {
        return cues;
    }

    public boolean isEmpty() {
        return cues.isEmpty();
    }

    public int size() {
        return cues.size();
    }

    /**
     * Returns the index of the cue displayed at {@code seconds}, or {@code -1}.
     */
    public int indexAt(final double seconds) {
        int low = 0;
        int high = cues.size() - 1;
        int candidate = -1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (cues.get(mid).start() <= seconds) {
                candidate = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (candidate < 0) {
            return -1;
        }
        return seconds < cues.get(candidate).end() ? candidate : -1;
    }

    public SubtitleCue cueAt(final double seconds) {
        final int index = indexAt(seconds);
        return index < 0 ? null : cues.get(index);
    }

    public int indexOf(final SubtitleCue cue) {
        return cues.indexOf(cue);
    }

    public void writeSrt(final Path target) throws IOException {
        writeUtf8(target, toSrt());
    }

    public void writeVtt(final Path target) throws IOException {
        writeUtf8(target, toVtt());
    }

    private static void writeUtf8(final Path target, final String text) throws IOException {
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readUtf8(final Path source) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = Files.newInputStream(source)) {
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    public String toSrt() {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            final SubtitleCue cue = cues.get(i);
            out.append(i + 1).append(System.lineSeparator());
            out.append(srtTime(cue.start())).append(" --> ").append(srtTime(cue.end()))
                    .append(System.lineSeparator());
            out.append(cue.text()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return out.toString();
    }

    public String toVtt() {
        final StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (final SubtitleCue cue : cues) {
            out.append(vttTime(cue.start())).append(" --> ").append(vttTime(cue.end())).append('\n');
            out.append(cue.text()).append("\n\n");
        }
        return out.toString();
    }

    public static SubtitleTrack readSrt(final Path source) throws IOException {
        final String text = readUtf8(source)
                .replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        final List<SubtitleCue> parsed = new ArrayList<>();
        for (final String block : text.split("\n\\s*\n")) {
            final String[] lines = block.split("\n");
            int timingLine = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timingLine = i;
                    break;
                }
            }
            if (timingLine < 0) {
                continue;
            }
            final String[] times = lines[timingLine].split("-->");
            if (times.length != 2) {
                continue;
            }
            try {
                final double start = parseTime(times[0]);
                final double end = parseTime(times[1]);
                final StringBuilder body = new StringBuilder();
                for (int i = timingLine + 1; i < lines.length; i++) {
                    if (body.length() > 0) {
                        body.append(' ');
                    }
                    body.append(lines[i].strip());
                }
                final String bodyText = body.toString().replaceAll("<[^>]+>", "").strip();
                if (!bodyText.isEmpty()) {
                    parsed.add(new SubtitleCue(start, end, bodyText));
                }
            } catch (final RuntimeException ignored) {
                // Skip malformed cue, keep the rest.
            }
        }
        return new SubtitleTrack(parsed);
    }

    public static String srtTime(final double seconds) {
        return format(seconds, ',');
    }

    public static String vttTime(final double seconds) {
        return format(seconds, '.');
    }

    private static String format(final double seconds, final char millisSeparator) {
        final long totalMillis = Math.max(0, Math.round(seconds * 1000));
        final long h = totalMillis / 3_600_000;
        final long m = (totalMillis % 3_600_000) / 60_000;
        final long s = (totalMillis % 60_000) / 1000;
        final long ms = totalMillis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, millisSeparator, ms);
    }

    private static double parseTime(final String value) {
        final String cleaned = value.strip().replace(',', '.');
        final String[] parts = cleaned.split(":");
        if (parts.length == 3) {
            return Integer.parseInt(parts[0].strip()) * 3600.0
                    + Integer.parseInt(parts[1].strip()) * 60.0
                    + Double.parseDouble(parts[2].strip());
        }
        if (parts.length == 2) {
            return Integer.parseInt(parts[0].strip()) * 60.0 + Double.parseDouble(parts[1].strip());
        }
        return Double.parseDouble(cleaned);
    }
}
