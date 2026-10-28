package dev.tubescribe;

import dev.tubescribe.media.AudioChunker;
import dev.tubescribe.media.MediaDownloader;
import dev.tubescribe.media.MediaException;
import dev.tubescribe.media.Quality;
import dev.tubescribe.media.StreamMerger;
import dev.tubescribe.net.VideoInfo;
import dev.tubescribe.net.YouTubeClient;
import dev.tubescribe.net.YouTubeException;
import dev.tubescribe.subs.SubtitleTrack;
import dev.tubescribe.transcribe.GroqTranscriber;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * End-to-end orchestration on Android: YouTube → media → 16 kHz WAV →
 * Groq Whisper → subtitle cues, with a per-video disk cache.
 *
 * <p>Unlike the desktop original this pipeline is cloud-only (Groq) and uses
 * MediaCodec/MediaMuxer instead of FFmpeg for audio extraction and stream
 * merging.</p>
 */
public final class Pipeline {
    private static final Logger LOG = Logger.getLogger(Pipeline.class.getName());

    private final File cacheRoot;
    private final List<String> groqKeys;
    private final MediaDownloader mediaDownloader = new MediaDownloader();

    public Pipeline(final File cacheRoot, final List<String> groqKeys) {
        this.cacheRoot = cacheRoot;
        this.groqKeys = groqKeys;
    }

    public record Result(File mediaFile, File subtitleFile, SubtitleTrack subtitles,
                         VideoInfo video, boolean fromCache) {
    }

    public Result run(final String input, final Quality quality,
                      final ProgressListener listener, final Cancellation cancel)
            throws Exception {
        cancel.throwIfCancelled();
        final String id = YouTubeClient.extractVideoId(input);
        if (id == null) {
            throw new YouTubeException(
                    "That does not look like a YouTube video link. Paste a link such as "
                            + "https://www.youtube.com/watch?v=dQw4w9WgXcQ or https://youtu.be/dQw4w9WgXcQ.");
        }
        final File cacheDir = new File(cacheRoot, id);
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        final File media = new File(cacheDir, "video.mp4");
        final File audioSource = new File(cacheDir, "audio-source.m4a");
        final File subtitleFile = new File(cacheDir, "subtitles.srt");
        final File metaFile = new File(cacheDir, "meta.properties");

        if (valid(media) && subtitleFile.isFile()) {
            cancel.throwIfCancelled();
            listener.update(1.0, "Loaded video and subtitles from cache");
            return new Result(media, subtitleFile, SubtitleTrack.readSrt(subtitleFile.toPath()),
                    readMeta(id, metaFile), true);
        }

        if (groqKeys.isEmpty()) {
            throw new IOException("Transcription needs at least one Groq API key.\n\n"
                    + "Add one under Settings → Groq API keys. Keys are stored encrypted "
                    + "on this device.");
        }

        if (!valid(media)) {
            //noinspection ResultOfMethodCallIgnored
            media.delete();
            listener.update(0.02, "Checking YouTube…");
            YouTubeClient.initialize(null);
            final VideoInfo resolved = new YouTubeClient().resolve(input, quality);
            final VideoInfo info = downloadMedia(input, quality, resolved, cacheDir, media,
                    audioSource, listener, cancel);
            saveMeta(metaFile, info);
        }

        final VideoInfo info = readMeta(id, metaFile);
        if (!subtitleFile.isFile()) {
            // No decoding: the compressed audio is split into upload-sized
            // m4a chunks by sample passthrough (a couple of seconds of file
            // copying) and sent to Groq as-is.
            listener.update(0.71, "Preparing audio for the cloud…");
            final File audioIn = valid(audioSource) ? audioSource : media;
            final List<AudioChunker.Chunk> chunks =
                    AudioChunker.split(audioIn, cacheDir, map(listener, 0.71, 0.05), cancel);
            try {
                cancel.throwIfCancelled();
                listener.update(0.76, "Transcribing with Groq Whisper…");
                final ProgressListener cloudProgress = map(listener, 0.76, 0.23);
                final SubtitleTrack track = new SubtitleTrack(
                        new GroqTranscriber(groqKeys).transcribe(chunks, cloudProgress, cancel));
                writeSubtitleFile(subtitleFile, track);
            } finally {
                for (final AudioChunker.Chunk chunk : chunks) {
                    //noinspection ResultOfMethodCallIgnored
                    chunk.file().delete();
                }
            }
        }

        // The transcription is stored; the bulky intermediates are no longer needed.
        //noinspection ResultOfMethodCallIgnored
        audioSource.delete();

        final SubtitleTrack track = SubtitleTrack.readSrt(subtitleFile.toPath());
        final StringBuilder status = new StringBuilder();
        if (info.height() > 0 && info.wantedHeight() > info.height()) {
            // The requested ceiling does not exist in this video; state what does.
            status.append(info.height()).append("p (no ").append(info.wantedHeight())
                    .append("p in this video");
            final String available = info.availableLabel();
            if (!available.isEmpty()) {
                status.append(" · available: ").append(available);
            }
            status.append(')');
        } else if (!info.qualityLabel().isBlank()) {
            status.append(info.qualityLabel());
        }
        final String qualityNote = status.toString();
        final String subtitleSummary = track.isEmpty() ? "no speech detected"
                : track.size() + " subtitle cues";
        listener.update(1.0, qualityNote.isEmpty()
                ? (track.isEmpty() ? "Media ready · no speech detected" : "Ready · " + subtitleSummary)
                : "Ready · " + qualityNote + " · " + subtitleSummary);
        return new Result(media, subtitleFile, track, info, false);
    }

    private VideoInfo downloadMedia(final String input, final Quality quality,
                                    final VideoInfo initial, final File cacheDir,
                                    final File media, final File audioSource,
                                    final ProgressListener listener,
                                    final Cancellation cancel) throws Exception {
        VideoInfo info = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (info.requiresMerge()) {
                    final File videoSource = new File(cacheDir, "video-source.mp4");
                    listener.update(0.10, "Downloading video stream…");
                    mediaDownloader.download(info.videoUrl(), videoSource.toPath(),
                            "Video · " + info.qualityLabel(), map(listener, 0.10, 0.36), cancel);
                    cancel.throwIfCancelled();
                    listener.update(0.47, "Downloading audio stream…");
                    mediaDownloader.download(info.audioUrl(), audioSource.toPath(), "Audio",
                            map(listener, 0.47, 0.14), cancel);
                    cancel.throwIfCancelled();
                    listener.update(0.62, "Merging streams…");
                    StreamMerger.merge(videoSource, audioSource, media, cancel);
                    //noinspection ResultOfMethodCallIgnored
                    videoSource.delete();
                } else {
                    listener.update(0.10, "Downloading video…");
                    mediaDownloader.download(info.muxedUrl(), media.toPath(),
                            "Video · " + info.qualityLabel(), map(listener, 0.10, 0.60), cancel);
                }
                if (!valid(media)) {
                    throw new MediaException("The downloaded video is empty.");
                }
                return info;
            } catch (final MediaException e) {
                final String message = e.getMessage() == null ? "" : e.getMessage();
                final boolean expired = message.contains("HTTP 403")
                        || message.contains("HTTP 410")
                        || message.toLowerCase(java.util.Locale.ROOT).contains("expired");
                if (attempt == 0 && expired) {
                    listener.update(0.09, "Media URL expired; refreshing YouTube link…");
                    LOG.info("Refreshing expired media URL for " + info.id());
                    info = new YouTubeClient().resolve(input, quality);
                    saveMeta(new File(cacheDir, "meta.properties"), info);
                    continue;
                }
                throw e;
            }
        }
        throw new MediaException("Could not download the media after refreshing the stream URL.");
    }

    // ------------------------------------------------------------- utilities

    private static void writeSubtitleFile(final File target, final SubtitleTrack track)
            throws IOException {
        final File temp = new File(target.getParentFile(), target.getName() + ".part");
        track.writeSrt(temp.toPath());
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean valid(final File file) {
        return file.isFile() && file.length() >= 1024;
    }

    private static ProgressListener map(final ProgressListener parent,
                                        final double offset, final double scale) {
        return (fraction, message) -> {
            final double value = fraction < 0
                    ? Math.min(0.999, offset + scale * 0.01)
                    : Math.min(0.999, offset + scale * Math.max(0, Math.min(1, fraction)));
            parent.update(value, message);
        };
    }

    private static void saveMeta(final File metaFile, final VideoInfo info) throws IOException {
        // Strip the dynamic bits (stream URLs) — only identity + quality data persist.
        final Properties properties = new Properties();
        properties.setProperty("id", info.id());
        properties.setProperty("title", info.title() == null ? "" : info.title());
        properties.setProperty("uploader", info.uploader() == null ? "" : info.uploader());
        properties.setProperty("duration", Long.toString(info.durationSeconds()));
        properties.setProperty("url", info.canonicalUrl() == null
                ? YouTubeClient.canonicalUrl(info.id()) : info.canonicalUrl());
        properties.setProperty("height", Integer.toString(info.height()));
        properties.setProperty("fps", Integer.toString(info.fps()));
        properties.setProperty("quality", info.qualityLabel() == null ? "" : info.qualityLabel());
        // Quality context for the player: what the video offers, and which
        // ceiling was requested at fetch time (0 when unknown).
        if (info.videoHeights() != null) {
            final StringBuilder heights = new StringBuilder();
            for (final Integer height : info.videoHeights()) {
                if (heights.length() > 0) {
                    heights.append(',');
                }
                heights.append(height);
            }
            properties.setProperty("heights", heights.toString());
        }
        properties.setProperty("wantedHeight", Integer.toString(info.wantedHeight()));
        final File temp = new File(metaFile.getParentFile(), metaFile.getName() + ".part");
        try (OutputStream out = Files.newOutputStream(temp.toPath())) {
            properties.store(out, "TubeScribe video metadata");
        }
        try {
            Files.move(temp.toPath(), metaFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static VideoInfo readMeta(final String id, final File metaFile) {
        final Properties properties = new Properties();
        if (metaFile.isFile()) {
            try (InputStream in = Files.newInputStream(metaFile.toPath())) {
                properties.load(in);
            } catch (final IOException e) {
                LOG.warning("Cannot read " + metaFile + ": " + e.getMessage());
            }
        }
        final String title = properties.getProperty("title", id);
        final String uploader = properties.getProperty("uploader", "");
        final long duration = parseLong(properties.getProperty("duration"), 0);
        final int height = (int) parseLong(properties.getProperty("height"), 0);
        final int fps = (int) parseLong(properties.getProperty("fps"), 0);
        final String quality = properties.getProperty("quality", height > 0 ? height + "p" : "");
        final String url = properties.getProperty("url", YouTubeClient.canonicalUrl(id));
        final List<Integer> heights = new ArrayList<>();
        for (final String part : properties.getProperty("heights", "").split(",")) {
            try {
                final int value = Integer.parseInt(part.strip());
                if (value > 0 && !heights.contains(value)) {
                    heights.add(value);
                }
            } catch (final NumberFormatException ignored) {
                // Older cache entries have no "heights" property at all.
            }
        }
        final int wantedHeight = (int) parseLong(properties.getProperty("wantedHeight"), 0);
        return new VideoInfo(id, title.isBlank() ? id : title, uploader, duration, url,
                null, null, null, height, fps, quality, heights, wantedHeight);
    }

    private static long parseLong(final String value, final long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    /** Cached videos, newest first, for the home screen's "Recent" list. */
    public static List<RecentVideo> recent(final File cacheRoot) {
        final List<RecentVideo> result = new ArrayList<>();
        final File[] dirs = cacheRoot.listFiles(File::isDirectory);
        if (dirs == null) {
            return result;
        }
        for (final File dir : dirs) {
            final File media = new File(dir, "video.mp4");
            final File subs = new File(dir, "subtitles.srt");
            if (!valid(media) || !subs.isFile()) {
                continue;
            }
            final VideoInfo info = readMeta(dir.getName(), new File(dir, "meta.properties"));
            result.add(new RecentVideo(info.id(), info.title(), info.uploader(),
                    info.qualityLabel(), info.durationSeconds(), info.canonicalUrl(),
                    media, subs, subs.lastModified()));
        }
        result.sort(Comparator.comparingLong(RecentVideo::modified).reversed());
        return result;
    }

    public record RecentVideo(String id, String title, String uploader, String qualityLabel,
                              long durationSeconds, String url, File mediaFile, File subtitleFile,
                              long modified) {
        public String metaLine() {
            return qualityLabel + " · " + Fmt.time(durationSeconds) + " · "
                    + (modified > 0 ? new java.text.SimpleDateFormat("MMM d",
                    java.util.Locale.getDefault()).format(new java.util.Date(modified)) : "");
        }
    }
}
