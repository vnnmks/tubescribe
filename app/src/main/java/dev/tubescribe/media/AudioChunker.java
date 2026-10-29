package dev.tubescribe.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import dev.tubescribe.TubeApp;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
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
 * couple of seconds of file copying here.</p>
 *
 * <p>Consecutive chunks are sequential: one chunk file runs from where the
 * previous file ends to its own seam, plus one tail window — there is no
 * special head and no seeking back. The audio just before a seam lives only
 * in the previous chunk's tail region, and the next request then begins
 * exactly where the previous request's conditioning text (the prior
 * transcript sent as the Whisper {@code prompt}) stops: the model hears a
 * continuation, not a replay. This
 * replaced the older rewound-head overlap after whisper repeatedly
 * suppressed the seam continuation: prompt text spoken again in the
 * audio is treated as an echo to skip, and the suppression extended
 * into the first words after it — at 01:50 of the reference video the
 * phrase "intend consciously" vanished from every overlapping-chunk
 * transcript. Cue ownership between chunk files is a plain partition by
 * file boundaries in {@code GroqTranscriber}; unmatched jitter is
 * handled there too.</p>
 */
public final class AudioChunker {
    private static final Logger LOG = Logger.getLogger(AudioChunker.class.getName());

    /** Stay safely under Groq's 25 MB upload limit (25,000,000 bytes). */
    public static final long MAX_CHUNK_BYTES = 24_000_000L;
    /** Smallest sane exclusive territory so tiny debug limits still work. */
    private static final long MIN_CHUNK_BYTES = 128 * 1024;
    /** Audio kept past every chunk's seam: this chunk still transcribes it
     * (as the end of its file, where it reads the seam phrase whole), and
     * the next chunk's file starts cleanly after it. */
    public static final long TAIL_US = 20_000_000L;
    /** Safety factor for the estimated tail margin bytes (VBR headroom). */
    private static final double TAIL_SAFETY = 1.5;
    private static final int SAMPLE_BUFFER_BYTES = 256 * 1024;

    private AudioChunker() {
    }

    /**
     * One uploadable audio file and the timeline positions it covers.
     *
     * @param file        the chunk written to disk
     * @param startUs     global PTS of the chunk's first audio sample; segment
     *                    timestamps returned for this file are offsets from here;
     *                    since chunks are sequential this is also the previous
     *                    chunk's file end
     * @param authStartUs the chunk's own first sample's PTS (the exclusive
     *                    territory starts with the file); with sequential files
     *                    no sample of this file exists in the previous chunk
     * @param authEndUs   exclusive territory end (the next seam); samples from
     *                    it on belong partly to this chunk's tail region
     * @param durationUs  container duration, for progress labels only
     */
    public record Chunk(File file, long startUs, long authStartUs, long authEndUs,
                        long durationUs) {
    }

    public static List<Chunk> split(final File media, final File cacheDir,
                                    final ProgressListener listener, final Cancellation cancel)
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

            final ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long totalBytes = media.length();
            long processed = 0;
            int index = 0;
            int lastPercent = -1;

            // Current chunk state. The chunk file covers, in global timeline
            // time: the region since the previous chunk's file ended (this
            // chunk's exclusive territory), then one tail window past the
            // declared seam. startUs is the real first sample's PTS, so
            // timestamps stay exact; audio before it lives in the previous
            // chunk's tail region.
            long chunkStartUs = 0;
            long authStartUs = 0;     // the chunk's own first sample's PTS; 0 for the first chunk
            long territoryEndUs = -1; // declared seam of this chunk; -1 = undecided
            long territoryBytes = 0;  // bytes inside the territory (no tail)
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

                // A territory ends where the byte budget would next be
                // exceeded: the sample about to be written starts the next
                // chunk. Writing continues for one tail window first.
                if (territoryEndUs < 0 && territoryBytes > 0 && territoryBytes + size
                        > territoryBudgetBytes(limitBytes, territoryBytes,
                        pts - authStartUs)) {
                    territoryEndUs = pts;
                    LOG.info("AudioChunker: seam after " + Fmt.time(authStartUs / 1_000_000.0)
                            + "–" + Fmt.time(territoryEndUs / 1_000_000.0));
                }

                if (territoryEndUs >= 0 && pts - territoryEndUs >= TAIL_US) {
                    // The tail window is written. Close the chunk and let the
                    // next file start at the very next sample — no rewind into
                    // the seam region: that audio lives in this chunk only, and
                    // the next request's prompt (the transcript so far) ends
                    // right where this sample begins, so the model hears a
                    // continuation instead of a reply of its own conditioning.
                    // The triggering sample becomes the next chunk's first.
                    closeChunk(muxer, chunkFile, chunks, chunkStartUs, authStartUs, territoryEndUs);
                    muxer = null;
                    chunkFile = null;
                    index++;
                    authStartUs = pts;
                    territoryEndUs = -1;
                    territoryBytes = 0;
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
                if (pts >= authStartUs && (territoryEndUs < 0 || pts < territoryEndUs)) {
                    territoryBytes += size;
                }
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
                // The final chunk's territory runs to the end of the audio;
                // samples were always written before the muxer existed.
                closeChunk(muxer, chunkFile, chunks, chunkStartUs, authStartUs, Long.MAX_VALUE);
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
        for (int i = 0; i < chunks.size(); i++) {
            final Chunk chunk = chunks.get(i);
            LOG.info("AudioChunker: chunk " + (i + 1) + "/" + chunks.size() + " covers "
                    + Fmt.time(chunk.startUs() / 1_000_000.0) + "–"
                    + Fmt.time((chunk.startUs() + chunk.durationUs()) / 1_000_000.0)
                    + ", territory " + (chunk.authEndUs() == Long.MAX_VALUE ? "end"
                    : Fmt.time(chunk.authEndUs() / 1_000_000.0)));
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
     * Byte budget for one chunk's exclusive territory. The tail window rides
     * on top of it and must still fit inside the upload limit, so the budget
     * is the limit minus one tail margin.
     */
    private static long territoryBudgetBytes(final long limitBytes, final long territoryBytes,
                                             final long spanUs) {
        return Math.max(MIN_CHUNK_BYTES, limitBytes - tailMarginBytes(
                limitBytes, territoryBytes, spanUs));
    }

    /** Estimated sample bytes in one tail window at the measured audio rate. */
    private static long tailMarginBytes(final long limitBytes, final long bytes,
                                        final long spanUs) {
        long margin = MIN_CHUNK_BYTES; // fallback until a rate can be measured
        if (spanUs > 1_000_000 && bytes > 0) {
            final double bytesPerSec = bytes / (spanUs / 1_000_000.0);
            margin = Math.round(bytesPerSec * (TAIL_US / 1_000_000.0) * TAIL_SAFETY);
        }
        // Never let the margin eat more than half the (debug) limit.
        final long maxMargin = Math.max(64 * 1024, (limitBytes - MIN_CHUNK_BYTES) / 2);
        return Math.min(margin, maxMargin);
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

    private static void closeChunk(final MediaMuxer muxer, final File file,
                                   final List<Chunk> chunks, final long startUs,
                                   final long authStartUs, final long authEndUs) throws IOException {
        muxer.stop();
        muxer.release();
        chunks.add(new Chunk(file, startUs, authStartUs, authEndUs, queryDuration(file)));
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
