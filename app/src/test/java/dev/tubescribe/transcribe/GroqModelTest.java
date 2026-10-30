package dev.tubescribe.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Pins the model ids sent to Groq and the saved-preference fallback. */
public class GroqModelTest {

    @Test
    public void idsMatchTheGroqApi() {
        assertEquals("whisper-large-v3-turbo", GroqModel.TURBO.id());
        assertEquals("whisper-large-v3", GroqModel.LARGE_V3.id());
    }

    @Test
    public void pickerLabelsNameTheTradeOff() {
        assertEquals("Max speed", GroqModel.TURBO.label());
        assertEquals("Whisper Large v3 Turbo", GroqModel.TURBO.modelName());
        assertEquals("Max accuracy", GroqModel.LARGE_V3.label());
        assertEquals("Whisper Large v3", GroqModel.LARGE_V3.modelName());
    }

    @Test
    public void savedNamesRoundTripWithATurboFallback() {
        for (final GroqModel model : GroqModel.values()) {
            assertSame(model, GroqModel.fromName(model.name(), GroqModel.TURBO));
        }
        assertSame(GroqModel.LARGE_V3, GroqModel.fromName("large_v3", GroqModel.TURBO));
        assertSame(GroqModel.TURBO, GroqModel.fromName("unknown", GroqModel.TURBO));
    }
}
