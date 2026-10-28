package dev.tubescribe.media;

/** Requested maximum video height. */
public enum Quality {
    P360("360p", 360),
    P720("720p", 720),
    P1080("1080p (best)", 1080);

    private final String label;
    private final int maxHeight;

    Quality(final String label, final int maxHeight) {
        this.label = label;
        this.maxHeight = maxHeight;
    }

    public String label() {
        return label;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public static Quality fromName(final String name, final Quality fallback) {
        for (final Quality quality : values()) {
            if (quality.name().equalsIgnoreCase(name)) {
                return quality;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return label;
    }
}
