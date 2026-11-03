package dev.tubescribe;

import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;

import dev.tubescribe.media.AudioChunker;
import dev.tubescribe.media.MediaDownloader;
import dev.tubescribe.media.MediaException;
import dev.tubescribe.media.Quality;
import dev.tubescribe.media.StreamCache;
import dev.tubescribe.media.StreamCacheDataSource;
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
 * MediaCodec/MediaMuxer instead of FFmpeg for audio extraction and chunking.
 * DASH video and audio are kept as separate tracks and merged at playback
 * time, so the video can be streamed while it is still incomplete.</p>
 */
public final class Pipeline {
    private static final Logger LOG = Logger.getLogger(Pipeline.class.getName());
    /** Layout before 0.4.2: one transcript plus a marker naming its model. */
    private static final String LEGACY_SUBTITLES = "subtitles.srt";
    private static final String MODEL_FILE = "transcript.model";
    /**
     * How much of a DASH video stream is fetched before the player opens.
     * Playback continues from this prefix on demand; the number is a head
     * start, not a limit (roughly 10–25 s depending on resolution).
     */
    private static final long PREFETCH_BYTES = 8L * 1024 * 1024;

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

    public record Result(File mediaFile, File audioFile, File subtitleFile, SubtitleTrack subtitles,
                         VideoInfo video, boolean fromCache, boolean videoComplete,
                         GroqModel model) {
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
        final File audio = new File(cacheDir, "audio.m4a");
        final File metaFile = new File(cacheDir, "meta.properties");
        migrateLegacySubtitles(cacheDir);

        // A DASH entry is a pair: video.mp4 (video-only, possibly still being
        // streamed) + audio.m4a (always complete). A progressive entry is one
        // muxed video.mp4 and has no separate audio.
        final CachedMeta cached = readMeta(id, metaFile);
        cleanupIntermediates(cacheDir, cached.dash());
        final File subtitleFile = subtitleFileFor(cacheDir, whisperModel);
        boolean dash = cached.dash();
        boolean videoComplete = dash ? StreamCache.isComplete(media) : valid(media);
        boolean audioComplete = valid(audio);
        boolean needVideo = !videoComplete;
        boolean needAudio = dash && !audioComplete;
        final boolean needSubs = !subtitleFile.isFile();

        // Every model keeps its own transcript, so switching the picker only
        // re-transcribes when that model has not run on this video yet.
        if (videoComplete && !needAudio && !needSubs) {
            VideoInfo served = cached.info();
            // Re-requesting a cached video at a higher quality upgrades the
            // download in place; the old copy only leaves the cache once the
            // new one is fully loaded.
            String note = null;
            boolean upgraded = false;
            if (quality.maxHeight() > served.height()) {
                final UpgradeOutcome outcome = upgradeCachedMedia(
                        input, quality, cacheDir, metaFile, media, audio, served, dash,
                        listener, cancel);
                if (outcome.info() != null) {
                    served = outcome.info();
                    dash = outcome.dash();
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
                return new Result(media, dash ? audio : null, subtitleFile, track, served,
                        false, true, whisperModel);
            }
            if (note != null) {
                listener.update(1.0, note + " · " + subtitleSummary);
            } else {
                listener.update(1.0, "Loaded video and subtitles from cache");
            }
            return new Result(media, dash ? audio : null, subtitleFile, track, served,
                    true, true, whisperModel);
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
        VideoInfo resolved = null;
        if (needVideo || needAudio) {
            listener.update(0.02, "Checking YouTube…");
            YouTubeClient.initialize(null);
            resolved = new YouTubeClient().resolve(input, quality);
        }
        if (needVideo) {
            final boolean resolvedDash = resolved.requiresMerge();
            final boolean formatChanged = cached.dash() != resolvedDash;
            final boolean qualityChanged = cached.info().height() > 0 && resolved.height() > 0
                    && cached.info().height() != resolved.height();
            // A partially streamed file belongs to one concrete stream. If the
            // requested quality or the stream layout changed, those bytes are
            // not reusable — start that stream over (subtitles stay valid).
            if (formatChanged || qualityChanged) {
                resetPartialStreams(media, audio);
                videoComplete = false;
                audioComplete = false;
            }
            dash = resolvedDash;
            needAudio = dash && !audioComplete;
        } else if (needAudio && (resolved == null || !resolved.requiresMerge())) {
            // The cached pair lost its audio and YouTube now only offers a
            // progressive stream: replace the entry rather than leave it mute.
            resetPartialStreams(media, audio);
            videoComplete = false;
            audioComplete = false;
            dash = false;
            needVideo = true;
            needAudio = false;
        }

        // DASH: the audio half downloads the (small) audio file first, then
        // chunks and transcribes it while the video half prefetches the first
        // few MiB of the video. The player opens as soon as the transcript and
        // that prefix are ready, and keeps streaming the rest on demand.
        // Progressive: the single file is also the transcription input, so the
        // video half must finish before the audio half can start, as before.
        final boolean runMedia = needVideo;
        final boolean runAudio = needAudio || needSubs;

        // Released once the file branch 2 reads is on disk — or as soon as
        // either branch dies. Every release path is covered, so it cannot hang.
        final CountDownLatch audioReady = new CountDownLatch(1);
        if (!runAudio || (dash ? audioComplete : videoComplete)) {
            audioReady.countDown();
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

        final Run work = new Run(input, quality, resolved, cacheDir, media, audio,
                subtitleFile, metaFile, dash, needAudio, needSubs, audioReady, progress,
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

        final VideoInfo info = runMedia ? downloaded : cached.info();
        final boolean videoCompleteNow = dash ? StreamCache.isComplete(media) : valid(media);
        final File audioFile = dash ? audio : null;
        if (!dash) {
            //noinspection ResultOfMethodCallIgnored
            audio.delete();
        }

        final SubtitleTrack track = SubtitleTrack.readSrt(subtitleFile.toPath());
        final String qualityNote = qualityNote(info);
        final String subtitleSummary = track.isEmpty() ? "no speech detected"
                : track.size() + " subtitle cues";
        listener.update(1.0, (videoCompleteNow ? "Ready" : "Streaming")
                + (qualityNote.isEmpty() ? "" : " · " + qualityNote)
                + (track.isEmpty() ? " · no speech detected" : " · " + subtitleSummary));
        return new Result(media, audioFile, subtitleFile, track, info, false,
                videoCompleteNow, whisperModel);
    }

    /** One run's shared state for the two parallel halves. */
    private final class Run {
        final String input;
        final Quality quality;
        final VideoInfo resolved;
        final File cacheDir;
        final File media;
        final File audio;
        final File subtitleFile;
        final File metaFile;
        final boolean dash;
        final boolean needAudio;
        final boolean needSubs;
        final CountDownLatch audioReady;
        final ProgressListener mediaProgress;
        final ProgressListener audioProgress;
        final Cancellation mediaCancel;
        final Cancellation audioCancel;

        Run(final String input, final Quality quality, final VideoInfo resolved,
            final File cacheDir, final File media, final File audio,
            final File subtitleFile, final File metaFile, final boolean dash,
            final boolean needAudio, final boolean needSubs,
            final CountDownLatch audioReady, final ProgressAggregator progress,
            final Cancellation mediaCancel, final Cancellation audioCancel) {
            this.input = input;
            this.quality = quality;
            this.resolved = resolved;
            this.cacheDir = cacheDir;
            this.media = media;
            this.audio = audio;
            this.subtitleFile = subtitleFile;
            this.metaFile = metaFile;
            this.dash = dash;
            this.needAudio = needAudio;
            this.needSubs = needSubs;
            this.audioReady = audioReady;
            this.mediaProgress = progress.branch(true);
            this.audioProgress = progress.branch(false);
            this.mediaCancel = mediaCancel;
            this.audioCancel = audioCancel;
        }
    }

    /**
     * Half 1 — the video side. For DASH it waits for the audio <em>file</em>
     * (a small download that branch 2 makes first), then prefetches the first
     * few MiB of the video and returns: playback streams the rest on demand.
     * For a progressive stream the single file is also the transcription
     * input, so it must be downloaded completely before branch 2 starts.
     */
    private VideoInfo produceMedia(final Run work) throws Exception {
        try {
            final VideoInfo info;
            if (work.dash) {
                awaitLatch(work.audioReady);
                work.mediaCancel.throwIfCancelled();
                info = prefetchVideo(work.input, work.quality, work.resolved, work.media,
                        map(work.mediaProgress, 0.0, 0.98), work.mediaCancel);
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
            saveMeta(work.metaFile, info, work.dash);
            work.mediaProgress.update(1.0, work.dash && !StreamCache.isComplete(work.media)
                    ? "Video ready · streaming" : "Video ready");
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
            final boolean separateAudio = work.dash;
            if (work.dash) {
                if (work.needAudio) {
                    downloadAudio(work.input, work.quality, work.resolved, work.audio,
                            map(work.audioProgress, 0.0, 0.20), work.audioCancel);
                }
                work.audioReady.countDown();
            } else {
                // Progressive: video.mp4 is the transcription input. It is
                // either already on disk (latch pre-released) or branch 1
                // releases the latch once the full download completes.
                awaitLatch(work.audioReady);
                work.audioCancel.throwIfCancelled();
            }
            if (work.needSubs) {
                transcribe(work, separateAudio);
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

        final File audioIn = work.dash && valid(work.audio) ? work.audio : work.media;
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
        } finally {
            for (final AudioChunker.Chunk chunk : chunks) {
                if (chunk.temporary()) {
                    //noinspection ResultOfMethodCallIgnored
                    chunk.file().delete();
                }
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

    /**
     * Fetches the first {@link #PREFETCH_BYTES} of a DASH video stream into
     * its range cache. The rest is fetched by the player's data source as it
     * plays (and seeks), so this deliberately stops long before the end.
     */
    private VideoInfo prefetchVideo(final String input, final Quality quality,
                                    final VideoInfo initial, final File target,
                                    final ProgressListener listener,
                                    final Cancellation cancel) throws Exception {
        VideoInfo info = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            // A refreshed resolve may land on a progressive stream (or none);
            // never fall through to a null URL on the retry.
            final String videoUrl = info.videoUrl();
            if (videoUrl == null || videoUrl.isBlank()) {
                throw new MediaException(attempt == 0
                        ? "YouTube did not provide a video stream URL."
                        : "YouTube no longer offers a separate video stream.");
            }
            try {
                listener.update(0.0, "Buffering video…");
                StreamCacheDataSource.prefetch(videoUrl, new StreamCache(target),
                        PREFETCH_BYTES, listener, cancel);
                cancel.throwIfCancelled();
                return info;
            } catch (final HttpDataSourceException e) {
                if (attempt == 0 && expired(e)) {
                    listener.update(0.0, "Media URL expired; refreshing YouTube link…");
                    LOG.info("Refreshing expired video URL for " + info.id());
                    info = new YouTubeClient().resolve(input, quality);
                    continue;
                }
                throw new MediaException("Video streaming failed: " + e.getMessage(), e);
            } catch (final IOException e) {
                if (attempt == 0 && expired(e)) {
                    listener.update(0.0, "Media URL expired; refreshing YouTube link…");
                    LOG.info("Refreshing expired video URL for " + info.id());
                    info = new YouTubeClient().resolve(input, quality);
                    continue;
                }
                throw new MediaException("Video streaming failed: " + e.getMessage(), e);
            }
        }
        throw new MediaException("Could not stream the video after refreshing the stream URL.");
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
        return expiredMessage(e.getMessage());
    }

    private static boolean expired(final HttpDataSourceException e) {
        if (e instanceof HttpDataSource.InvalidResponseCodeException) {
            final int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
            return code == 403 || code == 410;
        }
        return expiredMessage(e.getMessage());
    }

    private static boolean expired(final IOException e) {
        return expiredMessage(e.getMessage());
    }

    private static boolean expiredMessage(final String value) {
        final String message = value == null ? "" : value;
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
                                              final File media, final File audio,
                                              final VideoInfo cached, final boolean cachedDash,
                                              final ProgressListener listener,
                                              final Cancellation cancel) throws Exception {
        final File upgradedVideo = new File(cacheDir, "video-upgrade.mp4");
        final File upgradedAudio = new File(cacheDir, "audio-upgrade.m4a");
        final File videoBackup = new File(cacheDir, "video-old.mp4");
        final File indexBackup = new File(cacheDir, "video-old.mp4.idx");
        final File audioBackup = new File(cacheDir, "audio-old.m4a");
        try {
            cancel.throwIfCancelled();
            listener.update(0.05, "Checking YouTube…");
            YouTubeClient.initialize(null);
            final VideoInfo resolved = new YouTubeClient().resolve(input, quality);
            if (resolved.height() <= cached.height()) {
                LOG.info("No quality above " + cached.height() + "p available for " + cached.id()
                        + "; keeping the cached video");
                return new UpgradeOutcome(null,
                        "No higher quality available — keeping the cached video", cachedDash);
            }
            listener.update(0.06, "Upgrading cached video to " + resolved.qualityLabel() + "…");
            final VideoInfo fetched;
            final boolean newDash = resolved.requiresMerge();
            if (newDash) {
                fetched = downloadVideo(input, quality, resolved, upgradedVideo, false,
                        map(listener, 0.06, 0.54), cancel);
                cancel.throwIfCancelled();
                downloadAudio(input, quality, fetched, upgradedAudio,
                        map(listener, 0.62, 0.28), cancel);
                cancel.throwIfCancelled();
            } else {
                fetched = downloadVideo(input, quality, resolved, upgradedVideo, true,
                        map(listener, 0.06, 0.84), cancel);
            }
            if (!valid(upgradedVideo)) {
                throw new MediaException("The downloaded video is empty.");
            }
            if (newDash && !valid(upgradedAudio)) {
                throw new MediaException("The downloaded audio is empty.");
            }
            // Fully loaded and verified — now swap it in. The old files are
            // moved aside first so a failed swap can be undone.
            final boolean keptOldVideo = media.renameTo(videoBackup);
            if (!keptOldVideo && media.isFile()) {
                throw new IOException("Could not move the cached video aside.");
            }
            final File oldIndex = new File(media.getParentFile(), media.getName() + ".idx");
            final boolean keptOldIndex = oldIndex.renameTo(indexBackup);
            final boolean keptOldAudio = audio.isFile() && audio.renameTo(audioBackup);
            boolean swapped = false;
            try {
                move(upgradedVideo, media);
                if (newDash) {
                    StreamCache.markComplete(media);
                    move(upgradedAudio, audio);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    oldIndex.delete();
                    //noinspection ResultOfMethodCallIgnored
                    audio.delete();
                }
                swapped = true;
                saveMeta(metaFile, fetched, newDash);
                return new UpgradeOutcome(fetched, null, newDash);
            } catch (final Exception swapFailed) {
                restore(videoBackup, media, keptOldVideo);
                restore(indexBackup, oldIndex, keptOldIndex);
                restore(audioBackup, audio, keptOldAudio);
                throw swapFailed;
            } finally {
                // After a successful swap (or a successful restore) the
                // backups are obsolete. If the restore itself failed, the
                // backup may be the only copy left: keep it for
                // cleanupIntermediates() to recover on the next run.
                if (swapped || media.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    videoBackup.delete();
                    //noinspection ResultOfMethodCallIgnored
                    indexBackup.delete();
                }
                if (swapped || audio.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    audioBackup.delete();
                }
            }
        } catch (final OperationCancelledException e) {
            throw e;
        } catch (final Exception e) {
            // The expired-URL retry inside downloadMedia may have written meta
            // for the higher quality already; restore the cached entry's info.
            try {
                saveMeta(metaFile, cached, cachedDash);
            } catch (final Exception metaRestore) {
                LOG.warning("Could not restore cached meta: " + metaRestore);
            }
            LOG.warning("Upgrading the cached video failed, keeping it: " + e);
            return new UpgradeOutcome(null, "Upgrade failed — keeping the cached video",
                    cachedDash);
        } finally {
            // No transcription runs in the cache-hit path, so clean the
            // intermediates here.
            //noinspection ResultOfMethodCallIgnored
            upgradedVideo.delete();
            //noinspection ResultOfMethodCallIgnored
            upgradedAudio.delete();
        }
    }

    private static void move(final File from, final File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restore(final File backup, final File target, final boolean present) {
        if (!present) {
            return;
        }
        try {
            Files.move(backup.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final Exception e) {
            LOG.warning("Could not restore " + target + ": " + e);
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

    /** The transcript a given model produced for a cached video. */
    private static File subtitleFileFor(final File cacheDir, final GroqModel model) {
        return new File(cacheDir, "subtitles-" + model.name().toLowerCase(java.util.Locale.ROOT) + ".srt");
    }

    /**
     * Moves a pre-0.4.2 entry (one {@code subtitles.srt} plus a model marker,
     * or no marker when only turbo existed) to the per-model file name.
     */
    private static synchronized void migrateLegacySubtitles(final File cacheDir) {
        final File legacy = new File(cacheDir, LEGACY_SUBTITLES);
        if (!legacy.isFile()) {
            return;
        }
        final File marker = new File(cacheDir, MODEL_FILE);
        GroqModel model = GroqModel.TURBO;
        try {
            if (marker.isFile()) {
                model = GroqModel.fromId(new String(Files.readAllBytes(marker.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).strip());
            }
            if (model == null) {
                return;
            }
            final File target = subtitleFileFor(cacheDir, model);
            if (target.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                legacy.delete();
            } else {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
        } catch (final IOException e) {
            LOG.warning("Cannot migrate " + legacy + ": " + e.getMessage());
        }
    }

    private static boolean valid(final File file) {
        return file.isFile() && file.length() >= 1024;
    }

    /**
     * Removes half-finished files from older runs, and recovers an upgrade
     * that was killed after the old files were moved aside but before the new
     * ones were in place. The backup is a complete file, while the upgrade
     * temp may be a half-written download, so the backup always wins.
     */
    private static void cleanupIntermediates(final File cacheDir, final boolean dash) {
        final File media = new File(cacheDir, "video.mp4");
        if (restoreInterrupted(new File(cacheDir, "video-old.mp4"), media)) {
            restoreInterrupted(new File(cacheDir, "video-old.mp4.idx"),
                    new File(cacheDir, "video.mp4.idx"));
        }
        if (dash) {
            restoreInterrupted(new File(cacheDir, "audio-old.m4a"),
                    new File(cacheDir, "audio.m4a"));
        }
        for (final String name : List.of("audio-source.m4a", "video-source.mp4",
                "video-upgrade.mp4", "audio-upgrade.m4a", "video-old.mp4",
                "video-old.mp4.idx", "audio-old.m4a")) {
            //noinspection ResultOfMethodCallIgnored
            new File(cacheDir, name).delete();
        }
    }

    /** @return whether the backup was moved back into place. */
    private static boolean restoreInterrupted(final File backup, final File target) {
        if (target.isFile() || !backup.isFile()) {
            return false;
        }
        try {
            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (final IOException e) {
            LOG.warning("Could not restore " + target + ": " + e.getMessage());
            return false;
        }
    }

    /** Drops a partially streamed pair that belongs to a different stream. */
    private static void resetPartialStreams(final File media, final File audio) {
        //noinspection ResultOfMethodCallIgnored
        media.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(media.getParentFile(), media.getName() + ".idx").delete();
        //noinspection ResultOfMethodCallIgnored
        audio.delete();
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

    private static void saveMeta(final File metaFile, final VideoInfo info, final boolean dash)
            throws IOException {
        // Strip the dynamic bits (stream URLs) — only identity + quality data persist.
        final Properties properties = new Properties();
        properties.setProperty("stream", dash ? "dash" : "progressive");
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

    private static CachedMeta readMeta(final String id, final File metaFile) {
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
        final VideoInfo info = new VideoInfo(id, title.isBlank() ? id : title, uploader, duration, url,
                null, null, null, height, fps, quality, heights, wantedHeight);
        final boolean dash = "dash".equalsIgnoreCase(properties.getProperty("stream", ""));
        return new CachedMeta(info, dash);
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
            migrateLegacySubtitles(dir);
            final File media = new File(dir, "video.mp4");
            final File audio = new File(dir, "audio.m4a");
            final CachedMeta cached = readMeta(dir.getName(), new File(dir, "meta.properties"));
            final boolean dash = cached.dash();
            if (!valid(media) || (dash && !valid(audio))) {
                continue;
            }
            final boolean videoComplete = !dash || StreamCache.isComplete(media);
            final List<GroqModel> models = new ArrayList<>();
            File subs = null;
            GroqModel newestModel = null;
            for (final GroqModel candidate : GroqModel.values()) {
                final File file = subtitleFileFor(dir, candidate);
                if (!file.isFile()) {
                    continue;
                }
                models.add(candidate);
                if (subs == null || file.lastModified() > subs.lastModified()) {
                    subs = file;
                    newestModel = candidate;
                }
            }
            if (subs == null) {
                continue;
            }
            final VideoInfo info = cached.info();
            result.add(new RecentVideo(info.id(), info.title(), info.uploader(),
                    info.qualityLabel(), info.durationSeconds(), info.canonicalUrl(),
                    media, dash ? audio : null, videoComplete, subs, subs.lastModified(),
                    newestModel, models));
        }
        result.sort(Comparator.comparingLong(RecentVideo::modified).reversed());
        return result;
    }

    /** Result of an in-place cache upgrade attempt. */
    private record UpgradeOutcome(VideoInfo info, String note, boolean dash) {
    }

    /** Persisted identity/quality plus the cache layout (DASH pair vs progressive). */
    private record CachedMeta(VideoInfo info, boolean dash) {
    }

    public record RecentVideo(String id, String title, String uploader, String qualityLabel,
                              long durationSeconds, String url, File mediaFile, File audioFile,
                              boolean videoComplete, File subtitleFile, long modified,
                              GroqModel model, List<GroqModel> models) {
        /** The transcript the given cached model produced for this video. */
        public File subtitleFileFor(final GroqModel which) {
            return Pipeline.subtitleFileFor(mediaFile.getParentFile(), which);
        }

        public String metaLine() {
            return qualityLabel + " · " + Fmt.time(durationSeconds) + " · "
                    + (modified > 0 ? new java.text.SimpleDateFormat("MMM d",
                    java.util.Locale.getDefault()).format(new java.util.Date(modified)) : "")
                    + (models.size() == 1 ? " · " + model.label() : "")
                    + (videoComplete ? "" : " · streaming");
        }
    }
}
