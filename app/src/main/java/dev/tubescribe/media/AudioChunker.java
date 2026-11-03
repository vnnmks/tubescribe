package dev.tubescribe.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import dev.tubescribe.TubeApp;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.OperationCancelledException;
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
 * <p>Chunks are cut at sample boundaries once the accumulated sample bytes
 * approach Groq's 25 MB upload limit, so even hour-long videos cost only a
 * couple of seconds of file copying here. A source that is already a single
 * audio track below that limit is returned unchanged, because Groq accepts
 * the cached m4a as-is.</p>
 *
 * <p>Consecutive chunks are sequential: each file starts at the sample where
 * the previous file ended, so every audio sample is uploaded exactly once.
 * Transcript continuity across a seam comes from sending the previous
 * transcript as the next request's Whisper {@code prompt}, not from repeating
 * audio in both files. Cue ownership is a plain partition by file boundaries
 * in {@code GroqTranscriber}; unmatched jitter is handled there too.</p>
 *
 * <p>Callers that want the first upload to overlap the remaining muxing can
 * use the {@link #split(File, File, ProgressListener, Cancellation,
 * ChunkConsumer)} overload, which hands over each chunk as soon as its file
 * is complete.</p>
 */
public final class AudioChunker {
    private static final Logger LOG = Logger.getLogger(AudioChunker.class.getName());

    /** Stay safely under Groq's 25 MB upload limit (25,000,000 bytes). */
    public static final long MAX_CHUNK_BYTES = 24_000_000L;
    private static final int SAMPLE_BUFFER_BYTES = 256 * 1024;

    private AudioChunker() {
    }

    /**
     * One uploadable audio file and the timeline positions it covers.
     *
     * @param file        the chunk written to disk
     * @param startUs     global PTS of the chunk's first audio sample; segment
     *                    timestamps returned for this file are offsets from here
     * @param durationUs  container duration, for progress labels only
     * @param temporary   whether {@code file} is a private copy to delete after
     *                    transcription; false when it is the cached source
     */
    public record Chunk(File file, long startUs, long durationUs, boolean temporary) {
    }

    /**
     * Receives each chunk the moment its file is complete. Called on the
     * splitting thread, so a consumer may block while it uploads the chunk.
     */
    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(Chunk chunk) throws IOException;
    }

    /**
     * Supplies chunks in order. Used by the transcriber so it can start
     * uploading the first chunk while the remaining ones are still being
     * written.
     */
    public interface ChunkSource {
        /**
         * Blocks until the next chunk is complete.
         *
         * @return the next chunk, or {@code null} when the audio is exhausted
         */
        Chunk next() throws IOException;

        /** Total number of chunks, or 0 while the split is still running. */
        default int knownTotal() {
            return 0;
        }

        /** Duration of the source audio in seconds, or 0 when unknown. */
        default double sourceDurationSec() {
            return 0;
        }
    }

    public static List<Chunk> split(final File media, final File cacheDir,
                                    final ProgressListener listener, final Cancellation cancel)
            throws IOException {
        return split(media, cacheDir, listener, cancel, chunk -> {
        });
    }

    /**
     * Same as {@link #split(File, File, ProgressListener, Cancellation)}, but
     * passes every chunk to {@code onChunk} as soon as its file is complete.
     * That lets a caller on another thread upload the first chunk while later
     * chunks are still being muxed. The returned list holds every chunk for
     * cleanup and is complete only once this method returns.
     */
    public static List<Chunk> split(final File media, final File cacheDir,
                                    final ProgressListener listener, final Cancellation cancel,
                                    final ChunkConsumer onChunk)
            throws IOException {
        if (!media.isFile() || media.length() < 1024) {
            throw new MediaException("The audio source file is missing or empty: " + media);
        }
        final long limitBytes = chunkLimitBytes();
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

            // A source that is already a single audio track below the upload
            // limit needs no remuxing at all: Groq accepts the cached m4a
            // unchanged. This is the common DASH case, so most videos skip
            // the sample-copy pass entirely.
            if (media.length() <= limitBytes && isAudioOnly(extractor)) {
                final long durationUs = sourceDurationUs(format, media);
                if (durationUs > 0) {
                    final Chunk chunk = new Chunk(media, 0, durationUs, false);
                    chunks.add(chunk);
                    onChunk.accept(chunk);
                    listener.update(1.0, "Preparing audio for the cloud · 100%");
                    LOG.info("AudioChunker: " + media.getName()
                            + " already fits the upload limit; using it unchanged");
                    return chunks;
                }
            }

            final ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long totalBytes = media.length();
            long processed = 0;
            int index = 0;
            int lastPercent = -1;

            // Current chunk state.
            long chunkStartUs = 0;
            long chunkBytes = 0;      // every byte written to this chunk so far
            boolean haveSample = false;

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
                final long pts = extractor.getSampleTime();

                // Close the current chunk when the next sample would push it
                // past the upload limit. That sample opens the next chunk, so
                // the files remain exactly adjacent and no audio is repeated.
                if (chunkBytes > 0 && chunkBytes + size > limitBytes) {
                    LOG.info("AudioChunker: seam after "
                            + Fmt.time(chunkStartUs / 1_000_000.0) + "–"
                            + Fmt.time(pts / 1_000_000.0));
                    closeChunk(muxer, chunkFile, chunks, chunkStartUs, onChunk);
                    muxer = null;
                    chunkFile = null;
                    index++;
                    chunkBytes = 0;
                    haveSample = false;
                    // No `continue`: this sample opens the next chunk below.
                }

                if (muxer == null) {
                    chunkFile = new File(cacheDir, String.format(Locale.ROOT,
                            "groq-%04d.m4a", index));
                    muxer = new MediaMuxer(chunkFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    muxerTrack = muxer.addTrack(format);
                    muxer.start();
                }
                if (!haveSample) {
                    chunkStartUs = pts;
                    haveSample = true;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxerTrack, buffer, info);
                chunkBytes += size;
                processed += size;
                extractor.advance();
                final int percent = (int) Math.min(99, processed * 100 / totalBytes);
                if (percent >= lastPercent + 2) {
                    lastPercent = percent;
                    listener.update(Math.min(0.99, processed / (double) totalBytes),
                            "Preparing audio for the cloud · " + percent + "%");
                }
            }
            if (muxer != null) {
                closeChunk(muxer, chunkFile, chunks, chunkStartUs, onChunk);
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
            if (e instanceof OperationCancelledException) {
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
        for (int i = 0; i < chunks.size(); i++) {
            final Chunk chunk = chunks.get(i);
            LOG.info("AudioChunker: chunk " + (i + 1) + "/" + chunks.size() + " covers "
                    + Fmt.time(chunk.startUs() / 1_000_000.0) + "–"
                    + Fmt.time((chunk.startUs() + chunk.durationUs()) / 1_000_000.0));
        }
        LOG.info("AudioChunker: " + media.getName() + " -> " + chunks.size() + " chunk(s), "
                + media.length() / 1024 + "KB total");
        return chunks;
    }

    // ------------------------------------------------------------- utilities

    /** Effective per-chunk byte limit; a debug preference can shrink it to
     * force multiple sequential chunks on short test media. */
    private static long chunkLimitBytes() {
        try {
            final long debug = TubeApp.debugChunkLimit();
            if (debug > 0) {
                LOG.info("AudioChunker: debug chunk limit " + (debug / 1024) + "KB");
                return Math.min(debug, MAX_CHUNK_BYTES);
            }
        } catch (final RuntimeException ignored) {
            // App context not attached (unit tests): use the production limit.
        }
        return MAX_CHUNK_BYTES;
    }

    /**
     * Selects the first audio track in the container.
     */
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
                                   final List<Chunk> chunks, final long startUs,
                                   final ChunkConsumer onChunk) throws IOException {
        muxer.stop();
        muxer.release();
        final Chunk chunk = new Chunk(file, startUs, queryDuration(file), true);
        chunks.add(chunk);
        // The file is final here; hand it over before muxing the next one.
        onChunk.accept(chunk);
    }

    /**
     * Duration of the first audio track in {@code media}, or 0 when it cannot
     * be read. Callers that consume chunks while they are still being written
     * use this to report progress along the source timeline.
     */
    public static long audioDurationUs(final File media) {
        if (!media.isFile() || media.length() < 1024) {
            return 0L;
        }
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(media.getAbsolutePath());
            final int track = selectAudioTrack(extractor);
            if (track < 0) {
                return 0L;
            }
            return sourceDurationUs(extractor.getTrackFormat(track), media);
        } catch (final IOException | RuntimeException e) {
            return 0L;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /** Container duration of the selected track, or 0 when it is unknown. */
    private static long sourceDurationUs(final MediaFormat format, final File file) {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            final long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            if (durationUs > 0) {
                return durationUs;
            }
        }
        return queryDuration(file);
    }

    /** @return whether the container holds exactly one audio track. */
    private static boolean isAudioOnly(final MediaExtractor extractor) {
        if (extractor.getTrackCount() != 1) {
            return false;
        }
        final String mime = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME);
        return mime != null && mime.startsWith("audio/");
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
            // The source file is not ours to delete when it is used unchanged.
            if (chunk.temporary()) {
                //noinspection ResultOfMethodCallIgnored
                chunk.file().delete();
            }
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
