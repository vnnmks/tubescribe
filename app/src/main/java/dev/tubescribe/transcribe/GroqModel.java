package dev.tubescribe.transcribe;

/**
 * The Whisper models Groq's OpenAI-compatible transcription endpoint hosts.
 *
 * <p>Both accept the same request shape (m4a upload, {@code verbose_json} with
 * word timestamps) and fall under the same free-tier request and audio-second
 * limits. Turbo is the distilled variant — about a third of the price, with a
 * small accuracy trade-off; large-v3 is the full model.</p>
 */
public enum GroqModel {
    TURBO("whisper-large-v3-turbo", "Max speed", "Whisper Large v3 Turbo",
            "Groq's distilled Whisper model — the fastest and least expensive, "
                    + "with a small accuracy trade-off on difficult audio."),
    LARGE_V3("whisper-large-v3", "Max accuracy", "Whisper Large v3",
            "Groq's full Whisper large-v3 model — the most accurate, at about "
                    + "2.8× the cost of Max speed.");

    private final String id;
    private final String label;
    private final String modelName;
    private final String detail;

    GroqModel(final String id, final String label, final String modelName,
              final String detail) {
        this.id = id;
        this.label = label;
        this.modelName = modelName;
        this.detail = detail;
    }

    /** The model id sent in the multipart form. */
    public String id() {
        return id;
    }

    /** Trade-off name shown in the subtitle picker, e.g. "Max speed". */
    public String label() {
        return label;
    }

    /** The full model name shown under the picker label, e.g. "Whisper Large v3 Turbo". */
    public String modelName() {
        return modelName;
    }

    /** Full explanation for the About screen. */
    public String detail() {
        return detail;
    }

    /** Maps a saved preference name to a model, defaulting to turbo. */
    public static GroqModel fromName(final String name, final GroqModel fallback) {
        for (final GroqModel model : values()) {
            if (model.name().equalsIgnoreCase(name)) {
                return model;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return label;
    }
}
