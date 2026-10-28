package dev.tubescribe.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Splits the audio track of an MP4/M4A file into upload-sized {@code .m4a}
 * chunks by pure sample passthrough — no decoding. Groq's Whisper accepts
 * m4a natively, so skipping the decode-to-16kHz-WAV step removes the slowest
 * part of the pipeline entirely (measured ~58 s per 7 min of audio on a
 * mid-range phone, dominated by per-packet cross-process MediaCodec calls).
 *
 * <p>The chunks are cut at sample boundaries once the accumulated sample
 * bytes approach Groq's 25 MB upload limit, so even hour-long videos cost
 * only a couple of seconds of file copying here.</p>
 */
public final class AudioChunker {
    private static final Logger LOG = Logger.getLogger(AudioChunker.class.getName());

    /** Stay safely under Groq's 25 MB upload limit (25,000,000 bytes). */
    public static final long MAX_CHUNK_BYTES = 24_000_000L;
    private static final int SAMPLE_BUFFER_BYTES = 256 * 1024;

    private AudioChunker() {
    }

    /** One uploadable audio file plus the audio duration it covers. */
    public record Chunk(File file, long durationUs) {
    }

    public static List<Chunk> split(final File media, final File cacheDir,
                                    final ProgressListener listener, final Cancellation cancel)
            throws IOException {
        if (!media.isFile() || media.length() < 1024) {
            throw new MediaException("The audio source file is missing or empty: " + media);
        }
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        File chunkFile = null;
        int muxerTrack = -1;
        final List<Chunk> chunks = new ArrayList<>();
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(media.getAbsolutePath());
            final int track = selectAudioTrack(extractor);
            if (track < 0) {
                throw new MediaException("No audio track was found in " + media.getName() + ".");
            }
            extractor.selectTrack(track);
            final MediaFormat format = extractor.getTrackFormat(track);

            final ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long totalBytes = media.length();
            long processed = 0;
            long chunkBytes = 0;
            int index = 0;
            int lastPercent = -1;

            while (true) {
                cancel.throwIfCancelled();
                buffer.clear();
                final int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                if (size > SAMPLE_BUFFER_BYTES) {
                    throw new MediaException("An audio sample is larger than the chunk buffer.");
                }
                if (chunkBytes > 0 && chunkBytes + size > MAX_CHUNK_BYTES) {
                    closeChunk(muxer, chunkFile, chunks);
                    muxer = null;
                    chunkBytes = 0;
                }
                if (muxer == null) {
                    chunkFile = new File(cacheDir, String.format(Locale.ROOT,
                            "groq-%04d.m4a", index++));
                    muxer = new MediaMuxer(chunkFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    muxerTrack = muxer.addTrack(format);
                    muxer.start();
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxerTrack, buffer, info);
                chunkBytes += size;
                processed += size;
                extractor.advance();
                final int percent = (int) (processed * 100 / totalBytes);
                if (percent >= lastPercent + 2) {
                    lastPercent = percent;
                    listener.update(Math.min(0.99, processed / (double) totalBytes),
                            "Preparing audio for the cloud · " + percent + "%");
                }
            }
            if (muxer != null) {
                closeChunk(muxer, chunkFile, chunks);
                muxer = null;
            }
        } catch (final IOException | RuntimeException e) {
            if (muxer != null) {
                safeStop(muxer);
                release(muxer);
                muxer = null;
            }
            deleteAll(chunks, chunkFile);
            if (e instanceof IOException) {
                throw e;
            }
            throw new MediaException("Preparing the audio failed: " + e.getMessage(), e);
        } finally {
            if (muxer != null) {
                safeStop(muxer);
                release(muxer);
            }
            if (extractor != null) {
                extractor.release();
            }
        }
        if (chunks.isEmpty()) {
            deleteAll(chunks, null);
            throw new MediaException("The audio track contains no samples.");
        }
        long totalUs = 0;
        for (final Chunk chunk : chunks) {
            totalUs += chunk.durationUs();
        }
        LOG.info("AudioChunker: " + media.getName() + " -> " + chunks.size()
                + " chunk(s), " + Math.round(totalUs / 1_000_000.0) + "s of audio, "
                + media.length() / 1024 + "KB total");
        return chunks;
    }

    // ------------------------------------------------------------- utilities

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

    private static void closeChunk(final MediaMuxer muxer, final File file,
                                   final List<Chunk> chunks) throws IOException {
        muxer.stop();
        muxer.release();
        chunks.add(new Chunk(file, queryDuration(file)));
    }

    /** Duration of the audio track as written into the chunk's container. */
    private static long queryDuration(final File file) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat format = extractor.getTrackFormat(i);
                final String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")
                        && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION);
                }
            }
        } catch (final IOException ignored) {
            // A zero duration only shifts progress labels, never cue times
            // beyond the container's own granularity.
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
        return 0L;
    }

    private static void deleteAll(final List<Chunk> chunks, final File extra) {
        for (final Chunk chunk : chunks) {
            //noinspection ResultOfMethodCallIgnored
            chunk.file().delete();
        }
        chunks.clear();
        if (extra != null) {
            //noinspection ResultOfMethodCallIgnored
            extra.delete();
        }
    }

    private static void safeStop(final MediaMuxer muxer) {
        try {
            muxer.stop();
        } catch (final RuntimeException ignored) {
        }
    }

    private static void release(final MediaMuxer muxer) {
        try {
            muxer.release();
        } catch (final RuntimeException ignored) {
        }
    }
}
