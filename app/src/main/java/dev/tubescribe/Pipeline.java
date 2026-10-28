package dev.tubescribe;

import dev.tubescribe.media.AudioToWav;
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
        final File wav = new File(cacheDir, "audio16k.wav");
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
            if (!valid(wav)) {
                listener.update(0.71, "Extracting audio…");
                final File audioIn = valid(audioSource) ? audioSource : media;
                AudioToWav.extract(audioIn, wav, map(listener, 0.71, 0.12), cancel);
            }
            cancel.throwIfCancelled();
            listener.update(0.84, "Transcribing with Groq Whisper…");
            final ProgressListener cloudProgress = map(listener, 0.85, 0.14);
            final SubtitleTrack track = new SubtitleTrack(
                    new GroqTranscriber(groqKeys).transcribe(wav.toPath(), cloudProgress, cancel));
            writeSubtitleFile(subtitleFile, track);
        }

        // The transcription is stored; the bulky intermediates are no longer needed.
        //noinspection ResultOfMethodCallIgnored
        wav.delete();
        //noinspection ResultOfMethodCallIgnored
        audioSource.delete();

        final SubtitleTrack track = SubtitleTrack.readSrt(subtitleFile.toPath());
        listener.update(1.0, track.isEmpty()
                ? "Media ready · no speech detected"
                : "Ready · " + track.size() + " subtitle cues");
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
        return new VideoInfo(id, title.isBlank() ? id : title, uploader, duration, url,
                null, null, null, height, fps, quality);
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
            result.add(new RecentVideo(info.id(), info.title(), info.qualityLabel(),
                    info.durationSeconds(), media, subs, subs.lastModified()));
        }
        result.sort(Comparator.comparingLong(RecentVideo::modified).reversed());
        return result;
    }

    public record RecentVideo(String id, String title, String qualityLabel, long durationSeconds,
                              File mediaFile, File subtitleFile, long modified) {
        public String metaLine() {
            return qualityLabel + " · " + Fmt.time(durationSeconds) + " · "
                    + (modified > 0 ? new java.text.SimpleDateFormat("MMM d",
                    java.util.Locale.getDefault()).format(new java.util.Date(modified)) : "");
        }
    }
}
