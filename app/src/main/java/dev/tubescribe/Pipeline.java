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
import dev.tubescribe.transcribe.GroqModel;
import dev.tubescribe.transcribe.GroqTranscriber;
import dev.tubescribe.util.Cancellation;
import dev.tubescribe.util.Fmt;
import dev.tubescribe.util.OperationCancelledException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    /** Records which Whisper model produced {@code subtitles.srt}. */
    private static final String MODEL_FILE = "transcript.model";

    private final File cacheRoot;
    private final List<String> groqKeys;
    private final GroqModel whisperModel;
    private final MediaDownloader mediaDownloader = new MediaDownloader();

    public Pipeline(final File cacheRoot, final List<String> groqKeys,
                    final GroqModel whisperModel) {
        this.cacheRoot = cacheRoot;
        this.groqKeys = groqKeys;
        this.whisperModel = whisperModel;
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
        final File modelFile = new File(cacheDir, MODEL_FILE);
        final File metaFile = new File(cacheDir, "meta.properties");

        // Cached subtitles remember the model that produced them, so switching
        // the picker re-transcribes instead of silently serving the old
        // model's transcript. Entries from builds that predate the choice have
        // no marker and were all turbo.
        final boolean sameModel = subtitleModelMatches(modelFile, whisperModel);
        if (valid(media) && subtitleFile.isFile() && sameModel) {
            final VideoInfo cached = readMeta(id, metaFile);
            VideoInfo served = cached;
            // Re-requesting a cached video at a higher quality upgrades the
            // download in place; the old copy only leaves the cache once the
            // new one is fully loaded.
            String note = null;
            boolean upgraded = false;
            if (quality.maxHeight() > cached.height()) {
                final UpgradeOutcome outcome = upgradeCachedMedia(
                        input, quality, cacheDir, metaFile, media, audioSource, cached, listener, cancel);
                if (outcome.info() != null) {
                    served = outcome.info();
                    upgraded = true;
                } else {
                    note = outcome.note();
                }
            }
            cancel.throwIfCancelled();
            final SubtitleTrack track = SubtitleTrack.readSrt(subtitleFile.toPath());
            final String subtitleSummary = track.isEmpty()
                    ? "no speech detected" : (track.size() + " subtitle cues");
            if (upgraded) {
                listener.update(1.0, "Upgraded · " + qualityNote(served) + " · " + subtitleSummary);
                return new Result(media, subtitleFile, track, served, false);
            }
            if (note != null) {
                listener.update(1.0, note + " · " + subtitleSummary);
            } else {
                listener.update(1.0, "Loaded video and subtitles from cache");
            }
            return new Result(media, subtitleFile, track, served, true);
        }

        final boolean needMedia = !valid(media);
        // A cached transcript from another model is stale: re-run the
        // transcription (the cached video itself is still reused) and let the
        // new subtitles atomically replace the old ones on success.
        final boolean needSubs = !subtitleFile.isFile() || !sameModel;
        if (needMedia) {
            //noinspection ResultOfMethodCallIgnored
            media.delete();
        }
        // Fail before a single byte of video is fetched when transcription
        // cannot run. An unreadable key still surfaces during the upload.
        if (needSubs && groqKeys.isEmpty()) {
            throw new IOException("Transcription needs at least one Groq API key.\n\n"
                    + "Add one under Settings → Groq API keys. Keys are stored encrypted "
                    + "on this device.");
        }

        // One resolve feeds both halves of the work. It is a single small page
        // fetch, so it stays on this thread before anything forks.
        VideoInfo resolvedOrNull = null;
        if (needMedia) {
            listener.update(0.02, "Checking YouTube…");
            YouTubeClient.initialize(null);
            resolvedOrNull = new YouTubeClient().resolve(input, quality);
        }
        final VideoInfo resolved = resolvedOrNull;

        // DASH videos arrive as two files, so the media half (video → mux) and
        // the audio half (audio → chunks → Whisper) run side by side: the wait
        // becomes max(video+merge, audio+chunk+transcribe) instead of the sum.
        // A progressive stream is a single file that doubles as the
        // transcription input, so that path stays serial, as before.
        final boolean dash = needMedia && resolved.requiresMerge();
        final boolean runMedia = needMedia;
        final boolean runAudio = dash || needSubs;

        // Released once the file branch 2 reads is on disk — or as soon as
        // either branch dies. Branch 1 waits on it before muxing, branch 2
        // before chunking; every release path is covered, so it cannot hang.
        final CountDownLatch audioReady = new CountDownLatch(1);
        if (!runMedia) {
            audioReady.countDown();     // video.mp4 is already on disk
        }

        final ProgressAggregator progress = new ProgressAggregator(listener, runMedia, runAudio);
        final Cancellation mediaCancel = new Cancellation();
        final Cancellation audioCancel = new Cancellation();
        final Runnable stopBoth = cancel.addHook(() -> {
            mediaCancel.cancel();
            audioCancel.cancel();
        });
        // Whichever branch is released also opens the latch the other may be
        // waiting on, so no branch can stay blocked behind a dead sibling.
        mediaCancel.addHook(audioReady::countDown);
        audioCancel.addHook(audioReady::countDown);

        final Run work = new Run(input, quality, resolved, cacheDir, media, audioSource,
                subtitleFile, metaFile, dash, needSubs, audioReady, progress,
                mediaCancel, audioCancel);

        VideoInfo downloaded = null;
        ExecutorService pool = null;
        try {
            if (runMedia && runAudio) {
                pool = Executors.newFixedThreadPool(2);
                final Future<VideoInfo> mediaFuture =
                        pool.submit(() -> produceMedia(work));
                final Future<Void> audioFuture = pool.submit(() -> {
                    produceAudio(work);
                    return null;
                });
                downloaded = join(mediaFuture, audioFuture, mediaCancel, audioCancel);
            } else if (runMedia) {
                downloaded = produceMedia(work);
            } else {
                produceAudio(work);
            }
        } finally {
            stopBoth.run();
            if (pool != null) {
                pool.shutdownNow();
            }
        }

        final VideoInfo info = runMedia ? downloaded : readMeta(id, metaFile);

        // The transcription is stored; the bulky intermediates are no longer needed.
        //noinspection ResultOfMethodCallIgnored
        audioSource.delete();

        final SubtitleTrack track = SubtitleTrack.readSrt(subtitleFile.toPath());
        final String qualityNote = qualityNote(info);
        final String subtitleSummary = track.isEmpty() ? "no speech detected"
                : track.size() + " subtitle cues";
        listener.update(1.0, qualityNote.isEmpty()
                ? (track.isEmpty() ? "Media ready · no speech detected" : "Ready · " + subtitleSummary)
                : "Ready · " + qualityNote + " · " + subtitleSummary);
        return new Result(media, subtitleFile, track, info, false);
    }

    /** One run's shared state for the two parallel halves. */
    private final class Run {
        final String input;
        final Quality quality;
        final VideoInfo resolved;
        final File cacheDir;
        final File media;
        final File audioSource;
        final File subtitleFile;
        final File metaFile;
        final boolean dash;
        final boolean needSubs;
        final CountDownLatch audioReady;
        final ProgressListener mediaProgress;
        final ProgressListener audioProgress;
        final Cancellation mediaCancel;
        final Cancellation audioCancel;

        Run(final String input, final Quality quality, final VideoInfo resolved,
            final File cacheDir, final File media, final File audioSource,
            final File subtitleFile, final File metaFile, final boolean dash,
            final boolean needSubs,
            final CountDownLatch audioReady, final ProgressAggregator progress,
            final Cancellation mediaCancel, final Cancellation audioCancel) {
            this.input = input;
            this.quality = quality;
            this.resolved = resolved;
            this.cacheDir = cacheDir;
            this.media = media;
            this.audioSource = audioSource;
            this.subtitleFile = subtitleFile;
            this.metaFile = metaFile;
            this.dash = dash;
            this.needSubs = needSubs;
            this.audioReady = audioReady;
            this.mediaProgress = progress.branch(true);
            this.audioProgress = progress.branch(false);
            this.mediaCancel = mediaCancel;
            this.audioCancel = audioCancel;
        }
    }

    /**
     * Half 1 — the video side: fetch it, then mux it into {@code video.mp4}.
     * For DASH it waits only for the audio <em>file</em> (a small download that
     * branch 2 makes first), never for the transcription; both usually finish
     * while Whisper is still running.
     */
    private VideoInfo produceMedia(final Run work) throws Exception {
        try {
            final File videoSource = new File(work.cacheDir, "video-source.mp4");
            final VideoInfo info;
            if (work.dash) {
                info = downloadVideo(work.input, work.quality, work.resolved, videoSource, false,
                        map(work.mediaProgress, 0.0, 0.80), work.mediaCancel);
                work.mediaCancel.throwIfCancelled();
                awaitLatch(work.audioReady);
                work.mediaCancel.throwIfCancelled();
                work.mediaProgress.update(0.85, "Merging streams…");
                StreamMerger.merge(videoSource, work.audioSource, work.media, work.mediaCancel);
                //noinspection ResultOfMethodCallIgnored
                videoSource.delete();
                if (!valid(work.media)) {
                    throw new MediaException("The downloaded video is empty.");
                }
            } else {
                info = downloadVideo(work.input, work.quality, work.resolved, work.media, true,
                        work.mediaProgress, work.mediaCancel);
                if (!valid(work.media)) {
                    throw new MediaException("The downloaded video is empty.");
                }
                // A progressive stream is its own transcription input.
                work.audioReady.countDown();
            }
            // Persist the title/quality the moment the media is on disk: a
            // transcription failure in the other half must not cost them, and
            // a later retry that only needs subtitles reads them back here.
            saveMeta(work.metaFile, info);
            work.mediaProgress.update(1.0, "Video ready");
            return info;
        } catch (final Exception e) {
            // The media half died: stop the transcription half rather than
            // paying for a transcript that cannot be served.
            work.audioCancel.cancel();
            throw e;
        }
    }

    /** Half 2 — the audio side: fetch it (DASH only), chunk it, transcribe it. */
    private void produceAudio(final Run work) throws Exception {
        try {
            final boolean fetched;
            if (work.dash) {
                downloadAudio(work.input, work.quality, work.resolved, work.audioSource,
                        map(work.audioProgress, 0.0, 0.20), work.audioCancel);
                work.audioReady.countDown();
                fetched = true;
            } else {
                // Progressive (or the media is already cached): the input is
                // video.mp4, so wait for half 1 to produce it.
                awaitLatch(work.audioReady);
                work.audioCancel.throwIfCancelled();
                fetched = false;
            }
            if (work.needSubs) {
                transcribe(work, fetched);
            }
            work.audioProgress.update(1.0, work.needSubs ? "Subtitles ready" : "Audio ready");
        } catch (final Exception e) {
            // The transcription half died (bad key, no speech, cancelled):
            // stop the download instead of fetching media nobody can open.
            work.mediaCancel.cancel();
            throw e;
        }
    }

    /**
     * No decoding: the compressed audio is split into upload-sized m4a chunks
     * by sample passthrough (a couple of seconds of file copying) and sent to
     * Groq as-is. Runs on branch 2, overlapping the video download.
     */
    private void transcribe(final Run work, final boolean fetchedAudio) throws IOException {
        final double chunkAt = fetchedAudio ? 0.22 : 0.0;
        final double chunkSpan = fetchedAudio ? 0.10 : 0.12;
        final double whisperAt = fetchedAudio ? 0.34 : 0.14;
        final double whisperSpan = 0.98 - whisperAt;

        final File audioIn = valid(work.audioSource) ? work.audioSource : work.media;
        work.audioProgress.update(chunkAt, "Preparing audio for the cloud…");
        final List<AudioChunker.Chunk> chunks = AudioChunker.split(
                audioIn, work.cacheDir, map(work.audioProgress, chunkAt, chunkSpan), work.audioCancel);
        try {
            work.audioCancel.throwIfCancelled();
            work.audioProgress.update(whisperAt,
                    "Transcribing with " + whisperModel.modelName() + "…");
            final SubtitleTrack track = new SubtitleTrack(
                    new GroqTranscriber(groqKeys, whisperModel).transcribe(chunks,
                            map(work.audioProgress, whisperAt, whisperSpan), work.audioCancel));
            writeSubtitleFile(work.subtitleFile, track);
            writeTranscriptModel(new File(work.cacheDir, MODEL_FILE), whisperModel);
        } finally {
            for (final AudioChunker.Chunk chunk : chunks) {
                //noinspection ResultOfMethodCallIgnored
                chunk.file().delete();
            }
        }
    }

    /**
     * Downloads one side of the media with a single re-resolve retry when
     * YouTube reports an expired stream URL (HTTP 403/410). The listener sees
     * 0..1 for this file alone; callers place it into their own range with
     * {@link #map}.
     */
    private VideoInfo downloadVideo(final String input, final Quality quality,
                                    final VideoInfo initial, final File target,
                                    final boolean muxed,
                                    final ProgressListener listener,
                                    final Cancellation cancel) throws Exception {
        VideoInfo info = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                listener.update(0.0, muxed ? "Downloading video…" : "Downloading video stream…");
                mediaDownloader.download(muxed ? info.muxedUrl() : info.videoUrl(),
                        target.toPath(), "Video · " + info.qualityLabel(), listener, cancel);
                cancel.throwIfCancelled();
                return info;
            } catch (final MediaException e) {
                if (attempt == 0 && expired(e)) {
                    listener.update(0.0, "Media URL expired; refreshing YouTube link…");
                    LOG.info("Refreshing expired media URL for " + info.id());
                    info = new YouTubeClient().resolve(input, quality);
                    continue;
                }
                throw e;
            }
        }
        throw new MediaException("Could not download the media after refreshing the stream URL.");
    }

    /** The audio half's download, with the same expired-URL retry as the video. */
    private VideoInfo downloadAudio(final String input, final Quality quality,
                                    final VideoInfo initial, final File target,
                                    final ProgressListener listener,
                                    final Cancellation cancel) throws Exception {
        VideoInfo info = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                listener.update(0.0, "Downloading audio stream…");
                mediaDownloader.download(info.audioUrl(), target.toPath(), "Audio",
                        listener, cancel);
                cancel.throwIfCancelled();
                return info;
            } catch (final MediaException e) {
                if (attempt == 0 && expired(e)) {
                    listener.update(0.0, "Media URL expired; refreshing YouTube link…");
                    LOG.info("Refreshing expired audio URL for " + info.id());
                    info = new YouTubeClient().resolve(input, quality);
                    continue;
                }
                throw e;
            }
        }
        throw new MediaException("Could not download the audio after refreshing the stream URL.");
    }

    /** Googlevideo URLs expire; 403/410 (or an explicit "expired") says refresh. */
    private static boolean expired(final MediaException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("HTTP 403")
                || message.contains("HTTP 410")
                || message.toLowerCase(java.util.Locale.ROOT).contains("expired");
    }

    /** Blocks until the latch opens, turning an interrupted wait into a cancel. */
    private static void awaitLatch(final CountDownLatch latch)
            throws OperationCancelledException {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCancelledException();
        }
    }

    /**
     * Re-downloads an already cached video at a higher requested quality. The
     * new file is downloaded and verified under a temporary name and only
     * replaces the cached one (fully loaded) at the very end; on any failure —
     * or when YouTube offers nothing better — the lower-quality copy stays
     * in place and an explanatory note is returned.
     */
    private UpgradeOutcome upgradeCachedMedia(final String input, final Quality quality,
                                              final File cacheDir, final File metaFile,
                                              final File media, final File audioSource,
                                              final VideoInfo cached,
                                              final ProgressListener listener,
                                              final Cancellation cancel) throws Exception {
        final File upgraded = new File(cacheDir, "video-upgrade.mp4");
        try {
            cancel.throwIfCancelled();
            listener.update(0.05, "Checking YouTube…");
            YouTubeClient.initialize(null);
            final VideoInfo resolved = new YouTubeClient().resolve(input, quality);
            if (resolved.height() <= cached.height()) {
                LOG.info("No quality above " + cached.height() + "p available for " + cached.id()
                        + "; keeping the cached video");
                return new UpgradeOutcome(null,
                        "No higher quality available — keeping the cached video");
            }
            listener.update(0.06, "Upgrading cached video to " + resolved.qualityLabel() + "…");
            final VideoInfo fetched;
            if (resolved.requiresMerge()) {
                final File videoSource = new File(cacheDir, "video-source.mp4");
                fetched = downloadVideo(input, quality, resolved, videoSource, false,
                        map(listener, 0.06, 0.60), cancel);
                cancel.throwIfCancelled();
                downloadAudio(input, quality, fetched, audioSource,
                        map(listener, 0.66, 0.24), cancel);
                cancel.throwIfCancelled();
                listener.update(0.90, "Merging streams…");
                StreamMerger.merge(videoSource, audioSource, upgraded, cancel);
                //noinspection ResultOfMethodCallIgnored
                videoSource.delete();
            } else {
                fetched = downloadVideo(input, quality, resolved, upgraded, true,
                        map(listener, 0.06, 0.84), cancel);
            }
            if (!valid(upgraded)) {
                throw new MediaException("The downloaded video is empty.");
            }
            // Fully loaded and verified — now swap it in. The old file is
            // moved aside first so a failed swap can be undone.
            final File backup = new File(cacheDir, "video-old.mp4");
            final boolean keptOldFile = media.renameTo(backup);
            try {
                try {
                    Files.move(upgraded.toPath(), media.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(upgraded.toPath(), media.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                saveMeta(metaFile, fetched);
                return new UpgradeOutcome(fetched, null);
            } catch (final Exception swapFailed) {
                if (keptOldFile) {
                    try {
                        Files.move(backup.toPath(), media.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (final Exception restoreFailed) {
                        LOG.warning("Could not restore the old cached video: " + restoreFailed);
                    }
                }
                throw swapFailed;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
        } catch (final OperationCancelledException e) {
            throw e;
        } catch (final Exception e) {
            // The expired-URL retry inside downloadMedia may have written meta
            // for the higher quality already; restore the cached entry's info.
            try {
                saveMeta(metaFile, cached);
            } catch (final Exception metaRestore) {
                LOG.warning("Could not restore cached meta: " + metaRestore);
            }
            LOG.warning("Upgrading the cached video failed, keeping it: " + e);
            return new UpgradeOutcome(null, "Upgrade failed — keeping the cached video");
        } finally {
            // No transcription runs in the cache-hit path, so clean the
            // intermediates here.
            //noinspection ResultOfMethodCallIgnored
            upgraded.delete();
            //noinspection ResultOfMethodCallIgnored
            audioSource.delete();
        }
    }

    /** The one-line quality note shown in the pipeline's status message. */
    private static String qualityNote(final VideoInfo info) {
        if (info.height() > 0 && info.wantedHeight() > info.height()) {
            // The requested ceiling does not exist in this video; state what does.
            final StringBuilder status = new StringBuilder();
            status.append(info.height()).append("p (no ").append(info.wantedHeight())
                    .append("p in this video");
            final String available = info.availableLabel();
            if (!available.isEmpty()) {
                status.append(" · available: ").append(available);
            }
            return status.append(')').toString();
        }
        return info.qualityLabel();
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

    /** True when the cached subtitles came from {@code model}. A missing
     * marker means the entry predates the model choice, when only turbo ran. */
    private static boolean subtitleModelMatches(final File modelFile,
                                                final GroqModel model) {
        if (!modelFile.isFile()) {
            return model == GroqModel.TURBO;
        }
        try {
            final String cached = new String(Files.readAllBytes(modelFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
            return model.id().equals(cached);
        } catch (final IOException e) {
            LOG.warning("Cannot read " + modelFile + ": " + e.getMessage());
            return false;
        }
    }

    /** Records the model behind the cached subtitles. Marker failures are only
     * metadata loss — the run stays valid and the next one re-transcribes. */
    private static void writeTranscriptModel(final File target, final GroqModel model) {
        final File temp = new File(target.getParentFile(), target.getName() + ".part");
        try {
            Files.write(temp.toPath(), (model.id() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            LOG.warning("Cannot write " + target + ": " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
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

    /** Waits for both halves and throws the failure that actually failed the run. */
    private static VideoInfo join(final Future<VideoInfo> mediaFuture,
                                  final Future<Void> audioFuture,
                                  final Cancellation mediaCancel,
                                  final Cancellation audioCancel) throws Exception {
        VideoInfo mediaInfo = null;
        Exception mediaError = null;
        try {
            mediaInfo = mediaFuture.get();
        } catch (final ExecutionException e) {
            mediaError = unwrap(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            mediaCancel.cancel();
            audioCancel.cancel();
            throw new OperationCancelledException();
        }
        try {
            audioFuture.get();
        } catch (final ExecutionException e) {
            // Either half may have been cancelled *because* the other one
            // failed, so report whichever exception is the real one.
            throw firstFailure(mediaError, unwrap(e));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            mediaCancel.cancel();
            audioCancel.cancel();
            throw new OperationCancelledException();
        }
        if (mediaError != null) {
            throw mediaError;
        }
        return mediaInfo;
    }

    private static Exception unwrap(final ExecutionException e) {
        final Throwable cause = e.getCause();
        return cause instanceof Exception ? (Exception) cause : new IllegalStateException(cause);
    }

    /**
     * The exception the user sees when a half fails: a branch that was
     * cancelled <em>because</em> its sibling died reports
     * {@link OperationCancelledException}, so the genuine failure always wins —
     * otherwise a broken Groq key would look like a user cancel (and a dead
     * media URL like a successful run that simply stopped).
     */
    private static Exception firstFailure(final Exception mediaError, final Exception audioError) {
        if (mediaError == null) {
            return audioError;
        }
        if (audioError == null) {
            return mediaError;
        }
        final boolean mediaCancelled = mediaError instanceof OperationCancelledException;
        final boolean audioCancelled = audioError instanceof OperationCancelledException;
        if (mediaCancelled != audioCancelled) {
            return mediaCancelled ? audioError : mediaError;
        }
        if (!mediaCancelled) {
            LOG.warning("Both pipeline halves failed (" + mediaError + "; " + audioError
                    + "); reporting the media half.");
        }
        return mediaError;
    }

    /**
     * Merges the two concurrent halves' progress into one monotonic 0..1 value.
     *
     * <p>Each half reports its own 0..1 completion; the weighted sum is scaled
     * so the bar reaches 100% only when every running half is done and never
     * moves backwards. The status text carries one line per running half —
     * video first, subtitles second — so both halves' latest events stay
     * visible at once.</p>
     */
    private static final class ProgressAggregator {
        /** Whisper is the long pole, so its half owns more of the bar. */
        private static final double MEDIA_WEIGHT = 1.0;
        private static final double AUDIO_WEIGHT = 1.6;
        private static final double FLOOR = 0.02;
        private static final double SPAN = 0.96;

        private final ProgressListener out;
        private final boolean mediaActive;
        private final boolean audioActive;
        private double media;
        private double audio;
        private String mediaMessage = "Loading video…";
        private String audioMessage = "Preparing subtitles…";

        ProgressAggregator(final ProgressListener out, final boolean mediaActive,
                           final boolean audioActive) {
            this.out = out;
            this.mediaActive = mediaActive;
            this.audioActive = audioActive;
        }

        /** The listener one half reports to. */
        ProgressListener branch(final boolean mediaBranch) {
            return (fraction, message) -> update(mediaBranch, fraction, message);
        }

        private synchronized void update(final boolean mediaBranch, final double fraction,
                                         final String message) {
            // A source reporting "total unknown" (negative) only nudges its own
            // half forward; nothing ever moves backwards.
            final double local = fraction < 0
                    ? (mediaBranch ? media : audio) + 0.01
                    : Math.max(0.0, Math.min(1.0, fraction));
            if (mediaBranch) {
                media = Math.max(media, local);
                if (message != null && !message.isBlank()) {
                    mediaMessage = message;
                }
            } else {
                audio = Math.max(audio, local);
                if (message != null && !message.isBlank()) {
                    audioMessage = message;
                }
            }
            emit();
        }

        private void emit() {
            double weight = 0.0;
            double done = 0.0;
            if (mediaActive) {
                weight += MEDIA_WEIGHT;
                done += MEDIA_WEIGHT * media;
            }
            if (audioActive) {
                weight += AUDIO_WEIGHT;
                done += AUDIO_WEIGHT * audio;
            }
            final double fraction = weight <= 0 ? 0.999 : FLOOR + SPAN * (done / weight);
            out.update(Math.min(0.999, fraction), message());
        }

        private String message() {
            // One line per running half; the bar still merges them into one.
            if (mediaActive && audioActive) {
                return mediaMessage + "\n" + audioMessage;
            }
            return mediaActive ? mediaMessage : audioMessage;
        }
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

    /** Result of an in-place cache upgrade attempt. */
    private record UpgradeOutcome(VideoInfo info, String note) {
    }

    public record RecentVideo(String id, String title, String uploader, String qualityLabel,
                              long durationSeconds, String url, File mediaFile, File subtitleFile,
                              long modified) {        public String metaLine() {
            return qualityLabel + " · " + Fmt.time(durationSeconds) + " · "
                    + (modified > 0 ? new java.text.SimpleDateFormat("MMM d",
                    java.util.Locale.getDefault()).format(new java.util.Date(modified)) : "");
        }
    }
}
