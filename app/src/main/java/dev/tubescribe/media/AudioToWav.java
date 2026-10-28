package dev.tubescribe.media;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes the audio track of an MP4/M4A file to a 16 kHz mono 16-bit PCM WAV
 * using the platform's MediaCodec — the Android replacement for the desktop
 * app's FFmpeg-based extraction. The result is exactly what Whisper expects.
 */
public final class AudioToWav {
    public static final int TARGET_RATE = 16_000;

    private AudioToWav() {
    }

    public static void extract(final File media, final File wavTarget,
                               final ProgressListener listener, final Cancellation cancel)
            throws IOException {
        if (!media.isFile() || media.length() < 1024) {
            throw new MediaException("The audio source file is missing or empty: " + media);
        }
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        RandomAccessFile out = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(media.getAbsolutePath());
            final int track = selectAudioTrack(extractor);
            if (track < 0) {
                throw new MediaException("No audio track was found in " + media.getName() + ".");
            }
            extractor.selectTrack(track);
            final MediaFormat format = extractor.getTrackFormat(track);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new MediaException("The audio track has no codec information.");
            }
            final long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

            out = new RandomAccessFile(wavTarget, "rw");
            out.setLength(0);
            out.write(new byte[44]); // RIFF header, patched in finish()

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            final WavPcmSink sink = new WavPcmSink(out);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastReport = 0;

            while (!outputDone) {
                cancel.throwIfCancelled();
                if (!inputDone) {
                    final int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        final ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                        if (inBuffer == null) {
                            throw new MediaException("Decoder returned no input buffer.");
                        }
                        final int sampleSize = extractor.readSampleData(inBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                final int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        final ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                        if (outBuffer == null) {
                            throw new MediaDecoderException();
                        }
                        outBuffer.position(info.offset);
                        outBuffer.limit(info.offset + info.size);
                        sink.accept(outBuffer, info.size,
                                codec.getOutputFormat());
                        final long now = System.currentTimeMillis();
                        if (durationUs > 0 && now - lastReport >= 200) {
                            lastReport = now;
                            final double seconds = info.presentationTimeUs / 1_000_000.0;
                            listener.update(Math.min(0.99, seconds / (durationUs / 1_000_000.0)),
                                    "Extracting audio · " + (int) seconds + "s / "
                                            + (int) (durationUs / 1_000_000.0) + "s");
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            sink.finish();
            listener.update(1.0, "Audio extracted");
        } catch (final MediaException e) {
            wavTarget.delete();
            throw e;
        } catch (final IOException e) {
            wavTarget.delete();
            throw new MediaException("Audio extraction failed: " + e.getMessage(), e);
        } catch (final RuntimeException e) {
            wavTarget.delete();
            throw new MediaException("Audio extraction failed: " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (final RuntimeException ignored) {
                }
                codec.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ignored) {
                }
            }
        }
        if (!wavTarget.isFile() || wavTarget.length() < 1024) {
            wavTarget.delete();
            throw new MediaException("Audio extraction produced no data.");
        }
    }

    private static int selectAudioTrack(final MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /** Guard against a broken decoder rather than looping forever. */
    private static final class MediaDecoderException extends MediaException {
        MediaDecoderException() {
            super("The audio decoder returned no output buffer.");
        }
    }

    /**
     * Consumes decoded PCM (any channel count, 16-bit or float), downmixes to
     * mono, resamples to 16 kHz with linear interpolation and appends LE16
     * samples to the WAV file behind a placeholder header.
     */
    private static final class WavPcmSink {
        private static final int OUT_BYTES = 64 * 1024;

        private final RandomAccessFile out;
        private final byte[] buffer = new byte[OUT_BYTES];
        private int buffered;
        private long framesWritten;

        private int inRate;
        private int channels;
        private boolean floatPcm;
        private double step;
        private double nextPos;      // absolute input position of the next 16 kHz sample
        private long inCount;        // absolute input samples consumed so far
        private float lastSample;    // mono sample at absolute position inCount-1

        WavPcmSink(final RandomAccessFile out) {
            this.out = out;
        }

        void accept(final ByteBuffer data, final int size, final MediaFormat format)
                throws IOException {
            configure(format);
            final int frameBytes = channels * (floatPcm ? 4 : 2);
            final int frames = size / frameBytes;
            if (frames == 0) {
                return;
            }
            final float[] mono = new float[frames];
            data.order(ByteOrder.nativeOrder());
            for (int f = 0; f < frames; f++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    if (floatPcm) {
                        sum += data.getFloat((f * channels + c) * 4);
                    } else {
                        sum += data.getShort((f * channels + c) * 2) / 32768.0f;
                    }
                }
                mono[f] = sum / channels;
            }
            resample(mono, frames);
        }

        private void configure(final MediaFormat format) {
            if (inRate != 0) {
                return;
            }
            inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    && format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    == AudioFormat.ENCODING_PCM_FLOAT;
            step = inRate / (double) TARGET_RATE;
        }

        private void resample(final float[] mono, final int n) throws IOException {
            double pos = nextPos;
            while (pos < inCount + n) {
                final long floorPos = (long) Math.floor(pos);
                final double frac = pos - floorPos;
                final float s0;
                final float s1;
                if (floorPos < inCount) {
                    s0 = lastSample;
                    s1 = mono[0];
                } else {
                    final int i = (int) (floorPos - inCount);
                    s0 = mono[i];
                    s1 = i + 1 < n ? mono[i + 1] : s0;
                }
                writeSample((float) (s0 + (s1 - s0) * frac));
                pos += step;
            }
            nextPos = pos;
            if (n > 0) {
                lastSample = mono[n - 1];
                inCount += n;
            }
        }

        private void writeSample(final float value) throws IOException {
            final int sample = Math.round(Math.max(-1f, Math.min(1f, value)) * 32_767f);
            buffer[buffered++] = (byte) (sample & 0xFF);
            buffer[buffered++] = (byte) ((sample >> 8) & 0xFF);
            framesWritten++;
            if (buffered >= OUT_BYTES - 2) {
                flush();
            }
        }

        private void flush() throws IOException {
            if (buffered > 0) {
                out.write(buffer, 0, buffered);
                buffered = 0;
            }
        }

        void finish() throws IOException {
            flush();
            final int dataLength = (int) Math.min(Integer.MAX_VALUE, framesWritten * 2);
            out.seek(0);
            out.write(header(dataLength));
        }

        private static byte[] header(final int dataLength) {
            final byte[] h = new byte[44];
            ascii(h, 0, "RIFF");
            le32(h, 4, 36 + dataLength);
            ascii(h, 8, "WAVE");
            ascii(h, 12, "fmt ");
            le32(h, 16, 16);
            le16(h, 20, 1);                          // PCM
            le16(h, 22, 1);                          // mono
            le32(h, 24, TARGET_RATE);
            le32(h, 28, TARGET_RATE * 2);
            le16(h, 32, 2);                          // block align
            le16(h, 34, 16);                         // bits per sample
            ascii(h, 36, "data");
            le32(h, 40, dataLength);
            return h;
        }

        private static void ascii(final byte[] target, final int offset, final String value) {
            for (int i = 0; i < value.length(); i++) {
                target[offset + i] = (byte) value.charAt(i);
            }
        }

        private static void le16(final byte[] target, final int offset, final int value) {
            target[offset] = (byte) (value & 0xFF);
            target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        }

        private static void le32(final byte[] target, final int offset, final int value) {
            target[offset] = (byte) (value & 0xFF);
            target[offset + 1] = (byte) ((value >> 8) & 0xFF);
            target[offset + 2] = (byte) ((value >> 16) & 0xFF);
            target[offset + 3] = (byte) ((value >> 24) & 0xFF);
        }
    }
}
