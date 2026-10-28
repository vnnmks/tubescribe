package dev.tubescribe.util;

/** Small formatting helpers shared by the CLI and the UI. */
public final class Fmt {
    private Fmt() {
    }

    /** Formats a position as {@code mm:ss} or {@code h:mm:ss}. */
    public static String time(final double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            return "00:00";
        }
        final long total = Math.round(seconds);
        final long h = total / 3600;
        final long m = (total % 3600) / 60;
        final long s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    /** YouTube-style position: {@code 3:37}, {@code 16:46} or {@code 1:02:03}. */
    public static String clock(final double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            return "0:00";
        }
        final long total = Math.round(seconds);
        final long h = total / 3600;
        final long m = (total % 3600) / 60;
        final long s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    public static String bytes(final long value) {
        if (value < 1024) {
            return value + " B";
        }
        final double kb = value / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        final double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String percent(final double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0) {
            return "";
        }
        return Math.round(Math.min(1.0, Math.max(0.0, fraction)) * 100) + "%";
    }

    public static String ellipsize(final String value, final int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
