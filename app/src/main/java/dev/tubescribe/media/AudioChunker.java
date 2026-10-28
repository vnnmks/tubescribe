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
 * <p>By default consecutive chunks overlap: when a chunk's exclusive
 * territory reaches the byte budget, the seam location is noted but writing
 * continues for one more {@link #OVERLAP_US} window, and the next chunk then
 * seeks back to one window before the seam. Whisper therefore always hears
 * several seconds of context around every cut, which keeps sentences and
 * words at the boundary intact; cue ownership between the overlapping
 * transcriptions is decided by clean file-end partitioning in
 * {@code GroqTranscriber}, so the shared audio never appears twice. The
 * home-screen "Overlap audio chunks" toggle switches this off for
 * back-to-back chunks, in which case the transcriber keeps every chunk's own
 * head since no neighboring chunk hears it.</p>
 */
public final class AudioChunker {
    private static final Logger LOG = Logger.getLogger(AudioChunker.class.getName());

    /** Stay safely under Groq's 25 MB upload limit (25,000,000 bytes). */
    public static final long MAX_CHUNK_BYTES = 24_000_000L;
    /** Smallest sane exclusive territory so tiny debug limits still work. */
    private static final long MIN_CHUNK_BYTES = 128 * 1024;
    /** Audio kept on both sides of every chunk seam, so whisper has context. */
    public static final long OVERLAP_US = 20_000_000L;
    /** Safety factor for the estimated overlap bytes (VBR headroom). */
    private static final double OVERLAP_SAFETY = 1.5;
    private static final int SAMPLE_BUFFER_BYTES = 256 * 1024;

    private AudioChunker() {
    }

    /**
     * One uploadable audio file and the timeline positions it covers.
     *
     * @param file        the chunk written to disk
     * @param startUs     global PTS of the chunk's first audio sample; segment
     *                    timestamps returned for this file are offsets from here
     * @param authStartUs exclusive territory start (the previous seam); segments
     *                    starting before it also exist in the previous chunk
     * @param authEndUs   exclusive territory end (the next seam); segments
     *                    starting at or after it also exist in the next chunk
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
        final boolean overlap = chunkOverlapEnabled();
        final long overlapUs = overlap ? OVERLAP_US : 0L;
        LOG.info("AudioChunker: seam overlap " + (overlap ? "on" : "off"));
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
            // time: one head overlap window, its exclusive territory, then one
            // tail overlap window. startUs is the real first sample's PTS, so
            // timestamps stay exact even when a seek lands on a sync frame.
            long chunkStartUs = 0;
            long authStartUs = 0;     // previous seam; 0 for the first chunk
            long territoryEndUs = -1; // declared seam of this chunk; -1 = undecided
            long territoryBytes = 0;  // bytes inside the territory (no head overlap)
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
                // chunk. Writing continues for one more overlap window first.
                if (territoryEndUs < 0 && territoryBytes > 0 && territoryBytes + size
                        > territoryBudgetBytes(limitBytes, territoryBytes,
                        pts - authStartUs, overlap)) {
                    territoryEndUs = pts;
                    LOG.info("AudioChunker: seam after " + Fmt.time(authStartUs / 1_000_000.0)
                            + "–" + Fmt.time(territoryEndUs / 1_000_000.0));
                }

                if (territoryEndUs >= 0 && pts - territoryEndUs >= overlapUs) {
                    // The chunk is complete. Close it and start the next one
                    // with this sample.
                    final long seekUs = Math.max(0, territoryEndUs - OVERLAP_US);
                    closeChunk(muxer, chunkFile, chunks, chunkStartUs, authStartUs, territoryEndUs);
                    muxer = null;
                    chunkFile = null;
                    index++;
                    authStartUs = territoryEndUs;
                    territoryEndUs = -1;
                    territoryBytes = 0;
                    chunkBytes = 0;
                    haveSample = false;
                    if (overlapUs > 0) {
                        // Rewind: the next chunk begins one window before the
                        // seam, so both files contain the complete seam region.
                        // Drop this buffer: the rewind re-delivers the region,
                        // and the sample is written again when its time comes.
                        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        continue;
                    }
                    // Overlap off: chunks tile back-to-back. Keep streaming —
                    // this sample (the first past the seam) belongs to the
                    // fresh chunk.
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
                if (haveSample) {
                    // The final chunk's territory runs to the end of the audio.
                    closeChunk(muxer, chunkFile, chunks, chunkStartUs, authStartUs, Long.MAX_VALUE);
                } else {
                    // The audio ended inside the previous chunk's tail window,
                    // so this rewound chunk would duplicate covered audio.
                    safeStop(muxer);
                    release(muxer);
                    //noinspection ResultOfMethodCallIgnored
                    chunkFile.delete();
                }
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
     * force multiple chunks on short test media. */
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
     * User toggle read from the home screen: whether consecutive chunks
     * share a seam window. When off, chunks tile back-to-back and the
     * transcriber must keep every chunk's own head.
     */
    private static boolean chunkOverlapEnabled() {
        try {
            return TubeApp.chunkOverlapEnabled();
        } catch (final RuntimeException ignored) {
            // App context not attached (unit tests): keep the seam context.
            return true;
        }
    }

    /**
     * Byte budget for one chunk's exclusive territory. With overlap on, both
     * overlap windows ride on top of it and must still fit inside the upload
     * limit, so the budget is the limit minus two overlap margins.
     */
    private static long territoryBudgetBytes(final long limitBytes, final long territoryBytes,
                                             final long spanUs, final boolean overlap) {
        if (!overlap) {
            return Math.max(MIN_CHUNK_BYTES, limitBytes);
        }
        return Math.max(MIN_CHUNK_BYTES, limitBytes - 2 * overlapMarginBytes(
                limitBytes, territoryBytes, spanUs));
    }

    /** Estimated sample bytes in one overlap window at the measured audio rate. */
    private static long overlapMarginBytes(final long limitBytes, final long bytes,
                                           final long spanUs) {
        long margin = MIN_CHUNK_BYTES; // fallback until a rate can be measured
        if (spanUs > 1_000_000 && bytes > 0) {
            final double bytesPerSec = bytes / (spanUs / 1_000_000.0);
            margin = Math.round(bytesPerSec * (OVERLAP_US / 1_000_000.0) * OVERLAP_SAFETY);
        }
        // Never let both margins together eat more than half the (debug) limit.
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
