package dev.tubescribe.transcribe;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Streams 16 kHz mono PCM from a WAV file without loading the whole file.
 *
 * <p>Android port of the desktop's javax.sound-based reader: parses the RIFF
 * header directly and converts 8/16-bit (or IEEE-float) multi-channel PCM at
 * any rate to 16 kHz mono float samples with linear resampling.</p>
 */
public final class WavSource implements AutoCloseable {
    private static final int TARGET_RATE = 16_000;

    private final InputStream stream;
    private final long totalFrames;
    private final int sourceRate;
    private final int channels;
    private final int bitsPerSample; // negative marks IEEE-float input
    private final int frameBytes;
    private final double step;
    private final byte[] frame;      // one raw source frame

    private long nextFrameIndex;     // absolute index of the next frame to decode
    private float decodedSample;     // mono value of the most recently decoded frame
    private double nextPos;          // absolute input position of the next output sample

    private WavSource(final InputStream stream, final long dataOffset, final long totalFrames,
                      final int sourceRate, final int channels, final int bitsPerSample)
            throws IOException {
        this.stream = stream;
        this.totalFrames = totalFrames;
        this.sourceRate = sourceRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.frameBytes = channels * (Math.abs(bitsPerSample) / 8);
        this.frame = new byte[frameBytes];
        this.step = sourceRate / (double) TARGET_RATE;
        skipFully(stream, dataOffset);
    }

    public static WavSource open(final java.nio.file.Path path) throws IOException {
        final File file = path.toFile();
        long rate = 0;
        long channels = 0;
        long bits = 0;
        long dataOffset = -1;
        long dataFrames = -1;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 16 * 1024)) {
            final byte[] header = new byte[12];
            readFully(in, header);
            if (!ascii(header, 0, "RIFF") || !ascii(header, 8, "WAVE")) {
                throw new IOException("Not a WAV file: " + file.getName());
            }
            final byte[] chunkHeader = new byte[8];
            long position = 12;
            while (true) {
                readFully(in, chunkHeader);
                final long size = u32(chunkHeader, 4);
                position += 8;
                if (ascii(chunkHeader, 0, "fmt ")) {
                    final byte[] fmt = new byte[(int) Math.min(size, 40)];
                    readFully(in, fmt);
                    skipFully(in, size - fmt.length + (size & 1));
                    final int format = u16(fmt, 0);
                    if (format != 1 && format != 3) { // PCM or IEEE float
                        throw new IOException("Unsupported WAV encoding " + format
                                + " (expected PCM)");
                    }
                    channels = u16(fmt, 2);
                    rate = u32(fmt, 4);
                    bits = format == 3 ? -u16(fmt, 14) : u16(fmt, 14);
                    position += size + (size & 1);
                } else if (ascii(chunkHeader, 0, "data")) {
                    dataOffset = position;
                    dataFrames = bits == 0 ? -1 : size / (channels * (Math.abs(bits) / 8));
                    break;
                } else {
                    skipFully(in, size + (size & 1));
                    position += size + (size & 1);
                }
                if (size <= 0 && rate == 0) {
                    throw new IOException("Malformed WAV header: " + file.getName());
                }
            }
            if (rate <= 0 || channels <= 0 || bits == 0 || dataOffset < 0) {
                throw new IOException("WAV file has no fmt chunk: " + file.getName());
            }
        }
        // Reopen positioned at the data payload and stream from there.
        final BufferedInputStream data = new BufferedInputStream(new FileInputStream(file), 32 * 1024);
        return new WavSource(data, dataOffset, Math.max(0, dataFrames), (int) rate,
                (int) channels, (int) bits);
    }

    /**
     * Fills {@code target} with up to {@code target.length} 16 kHz mono samples.
     *
     * @return number of samples written; 0 at end of file
     */
    public int read(final float[] target) throws IOException {
        int produced = 0;
        while (produced < target.length) {
            final long wanted = (long) Math.floor(nextPos);
            if (wanted >= totalFrames) {
                break;
            }
            while (nextFrameIndex <= wanted) {
                decodeNextFrame();
            }
            target[produced++] = decodedSample;
            nextPos += step;
        }
        return produced;
    }

    private void decodeNextFrame() throws IOException {
        readFully(stream, frame);
        nextFrameIndex++;
        float sum = 0;
        final boolean floating = bitsPerSample < 0;
        final int sampleBytes = Math.abs(bitsPerSample) / 8;
        for (int c = 0; c < channels; c++) {
            final int offset = c * sampleBytes;
            if (floating) {
                final int bitsValue = (frame[offset] & 0xFF) | ((frame[offset + 1] & 0xFF) << 8)
                        | ((frame[offset + 2] & 0xFF) << 16) | ((frame[offset + 3] & 0xFF) << 24);
                sum += Float.intBitsToFloat(bitsValue);
            } else if (sampleBytes == 2) {
                final short value = (short) ((frame[offset] & 0xFF) | (frame[offset + 1] << 8));
                sum += value / 32768.0f;
            } else { // 8-bit unsigned
                sum += ((frame[offset] & 0xFF) - 128) / 128.0f;
            }
        }
        decodedSample = sum / channels;
    }

    public long totalFrames() {
        return totalFrames;
    }

    public double totalSeconds() {
        return totalFrames <= 0 ? 0 : totalFrames / (double) sourceRate;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    // ------------------------------------------------------------- primitives

    private static void readFully(final InputStream in, final byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            final int read = in.read(target, total, target.length - total);
            if (read < 0) {
                throw new EOFException("Unexpected end of WAV data");
            }
            total += read;
        }
    }

    private static void skipFully(final InputStream in, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Unexpected end of WAV data");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean ascii(final byte[] data, final int offset, final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (data[offset + i] != (byte) value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long u32(final byte[] data, final int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int u16(final byte[] data, final int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
