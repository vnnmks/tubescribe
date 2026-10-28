package dev.tubescribe.media;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import dev.tubescribe.util.Cancellation;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Muxes a DASH video-only MP4 and an audio-only M4A into a single playable
 * MP4 by sample passthrough (no re-encoding) — the Android replacement for
 * the desktop app's {@code ffmpeg merge}.
 */
public final class StreamMerger {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private StreamMerger() {
    }

    public static void merge(final File video, final File audio, final File target,
                             final Cancellation cancel) throws IOException {
        if (!video.isFile() || video.length() < 1024) {
            throw new MediaException("The video stream file is missing or empty.");
        }
        if (!audio.isFile() || audio.length() < 1024) {
            throw new MediaException("The audio stream file is missing or empty.");
        }
        target.delete();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            muxer = new MediaMuxer(target.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int[] videoTrack = addTrack(muxer, video, "video/", cancel);
            final int[] audioTrack = addTrack(muxer, audio, "audio/", cancel);
            muxer.start();
            started = true;
            copy(video, videoTrack[0], videoTrack[1], muxer, cancel);
            copy(audio, audioTrack[0], audioTrack[1], muxer, cancel);
        } catch (final MediaException e) {
            if (started) {
                safeStop(muxer);
            }
            release(muxer);
            target.delete();
            throw e;
        } catch (final RuntimeException e) {
            if (started) {
                safeStop(muxer);
            }
            release(muxer);
            target.delete();
            throw new MediaException("Merging the streams failed: " + e.getMessage(), e);
        }
        if (started) {
            muxer.stop();
        }
        release(muxer);
        if (!target.isFile() || target.length() < 1024) {
            target.delete();
            throw new MediaException("Merging produced an empty file.");
        }
    }

    /** @return {sourceTrackIndex, muxerTrackIndex} */
    private static int[] addTrack(final MediaMuxer muxer, final File file, final String mimePrefix,
                                  final Cancellation cancel) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                cancel.throwIfCancelled();
                final MediaFormat format = extractor.getTrackFormat(i);
                final String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith(mimePrefix)) {
                    return new int[]{i, muxer.addTrack(format)};
                }
            }
            throw new MediaException("No " + mimePrefix + " track found in " + file.getName());
        } finally {
            extractor.release();
        }
    }

    private static void copy(final File file, final int sourceTrack, final int muxerTrack,
                             final MediaMuxer muxer, final Cancellation cancel) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            extractor.selectTrack(sourceTrack);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            final android.media.MediaCodec.BufferInfo info =
                    new android.media.MediaCodec.BufferInfo();
            while (true) {
                cancel.throwIfCancelled();
                final int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxerTrack, buffer, info);
                buffer.clear();
                extractor.advance();
            }
        } finally {
            extractor.release();
        }
    }

    private static void safeStop(final MediaMuxer muxer) {
        try {
            if (muxer != null) {
                muxer.stop();
            }
        } catch (final RuntimeException ignored) {
        }
    }

    private static void release(final MediaMuxer muxer) {
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (final RuntimeException ignored) {
        }
    }
}
