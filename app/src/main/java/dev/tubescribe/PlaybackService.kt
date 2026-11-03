package dev.tubescribe

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.tubescribe.media.StreamCacheDataSource
import java.io.File

/**
 * Publishes the player's [MediaSession] so Android shows the media card in the
 * notification shade and on the lock screen. The player itself is owned by
 * [PlaybackHolder]; this service only exposes its session and lets Media3
 * promote it to a foreground service while playing.
 */
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        val session = PlaybackHolder.session
        if (session == null) {
            stopSelf()
        } else {
            addSession(session)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackHolder.session
}

/** Process-wide owner of the ExoPlayer and its session, shared by the player screen and the service. */
@OptIn(UnstableApi::class)
object PlaybackHolder {
    private var player: ExoPlayer? = null

    @Volatile
    var session: MediaSession? = null
        private set

    fun start(
        context: Context,
        mediaPath: String,
        audioPath: String?,
        videoUrl: String?,
        videoComplete: Boolean,
        title: String,
        artist: String,
        startMs: Long
    ): ExoPlayer {
        release()
        val app = context.applicationContext
        val videoFile = File(mediaPath)
        // While a DASH video is still being streamed, its local file is a
        // sparse prefix; the range data source fills the rest on demand.
        // A complete entry (and every progressive entry) plays straight from
        // disk. Audio is always complete, so it is a plain file source.
        val liveUrl = if (videoComplete) null else videoUrl?.takeIf { it.isNotBlank() }
        val videoFactory = if (liveUrl != null) {
            StreamCacheDataSource.Factory(videoFile, liveUrl)
        } else {
            DefaultDataSource.Factory(app)
        }
        val videoItem = MediaItem.Builder()
            .setUri(if (liveUrl != null) Uri.parse(liveUrl) else Uri.fromFile(videoFile))
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { null })
                    .build()
            )
            .build()
        val videoSource = ProgressiveMediaSource.Factory(videoFactory)
            .createMediaSource(videoItem)
        val mediaSource = if (!audioPath.isNullOrBlank()) {
            val audioItem = MediaItem.Builder()
                .setUri(Uri.fromFile(File(audioPath)))
                .setMimeType(MimeTypes.AUDIO_MP4)
                .build()
            val audioSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app))
                .createMediaSource(audioItem)
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
        val exo = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                setMediaSource(mediaSource)
                playWhenReady = true
                prepare()
                if (startMs > 0L) seekTo(startMs)
            }
        player = exo
        // Tapping the media card brings the running app back to the front.
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(app, exo).setSessionActivity(open).build()
        app.startService(Intent(app, PlaybackService::class.java))
        return exo
    }

    fun stop(context: Context) {
        release()
        val app = context.applicationContext
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun release() {
        session?.release()
        session = null
        player?.release()
        player = null
    }
}
