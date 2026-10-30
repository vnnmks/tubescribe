package dev.tubescribe.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.tubescribe.TubeApp
import dev.tubescribe.subs.CueBuilder
import dev.tubescribe.subs.SubtitleCue
import dev.tubescribe.subs.SubtitleTrack
import dev.tubescribe.util.Fmt
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Subtitles always assume a 16:9 video. Their max width is the full width of
// that frame in the windowed player; fullscreen keeps the exact same pixel
// width on purpose, so entering fullscreen never changes the subtitle's size.
private const val VIDEO_ASPECT_RATIO = 16f / 9f
private val WINDOWED_VIDEO_HEIGHT = 240.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(args: PlayerArgs, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val activity = remember(context) { context.findActivity() }

    // Fullscreen is driven by the device orientation while auto-rotate is on,
    // and by the maximize/restore button while it is off.
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val autoRotate = rememberAutoRotateEnabled()
    var manualFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = if (autoRotate) isLandscape else manualFullscreen

    // Keep the playback position across activity recreation (e.g. a rotation
    // on devices that still recreate despite configChanges).
    var restorePositionMs by rememberSaveable { mutableLongStateOf(0L) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(args.mediaPath))))
            playWhenReady = true
            prepare()
            if (restorePositionMs > 0L) seekTo(restorePositionMs)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            // Never leave the rest of the app locked in the player's
            // fullscreen orientation or with hidden system bars.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-rotate takes over the screen orientation as soon as it is enabled.
    // If it is switched off while we are already in landscape, keep the
    // fullscreen view and expose the restore button instead of snapping back.
    LaunchedEffect(autoRotate) {
        if (autoRotate) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            manualFullscreen = false
        } else if (isLandscape) {
            manualFullscreen = true
        }
    }

    // A manually expanded video stays landscape until the restore button (or
    // Back) is used, including after the activity is recreated.
    LaunchedEffect(manualFullscreen, autoRotate) {
        if (!autoRotate && manualFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // Immersive mode while fullscreen: hide status and navigation bars.
    DisposableEffect(fullscreen) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // Back leaves the player, but first restores a manually expanded video.
    BackHandler(enabled = fullscreen && !autoRotate) {
        manualFullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    val track = remember(args.subsPath) {
        runCatching { SubtitleTrack.readSrt(File(args.subsPath).toPath()) }
            .getOrDefault(SubtitleTrack.empty())
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            restorePositionMs = positionMs
            playing = player.isPlaying
            val duration = player.duration
            if (duration > 0) durationMs = duration
            if (!player.isPlaying) controlsVisible = true // paused/ended keeps controls up
            delay(120)
        }
    }
    // ExoPlayer-style: while playing, fade the controls out a few seconds after they show.
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // Transient "−10 s"/"+10 s" bubble after a double-tap seek (-1 left, +1 right).
    var seekNudge by remember { mutableStateOf(0) }
    LaunchedEffect(seekNudge) {
        if (seekNudge != 0) {
            delay(500)
            seekNudge = 0
        }
    }

    val positionSec = positionMs / 1000.0
    val currentIndex = track.indexAt(positionSec)

    var subsOn by rememberSaveable { mutableStateOf(true) }
    var autoFollow by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // ------------------------------------------- draggable subtitle overlay
    var videoSize by remember { mutableStateOf(IntSize.Zero) }
    var subsSize by remember { mutableStateOf(IntSize.Zero) }
    // Max subtitle width in raw pixels: 100% of the 16:9 frame in the windowed
    // (240 dp-tall) player. smallestScreenWidthDp is the portrait (short-side)
    // width and does not change with rotation or system bars, so fullscreen
    // keeps exactly the same pixel width — rotating never resizes the pill.
    val subtitleMaxWidthPx = with(LocalDensity.current) {
        minOf(
            configuration.smallestScreenWidthDp.dp.toPx(),
            WINDOWED_VIDEO_HEIGHT.toPx() * VIDEO_ASPECT_RATIO
        )
    }
    // User-placed vertical center as a fraction of the video height. Null means
    // the default bottom-center spot.
    var subsCenterY by remember {
        mutableStateOf(if (TubeApp.subtitlePlaced) TubeApp.subtitleCenterY else null)
    }
    // Effective rendered position (top-left, px); always kept inside the video
    // box. Written while the pill is laid out, so it always matches the CURRENT
    // cue's measured size.
    var renderPos by remember { mutableStateOf(Offset.Zero) }

    // Double-tapping the pill restores the default position.
    val resetSubtitleDefaults = {
        subsCenterY = null
        TubeApp.subtitlePlaced = false
        TubeApp.subtitleCenterX = 0.5f
        TubeApp.subtitleCenterY = 0.85f
    }

    // Persist the overlay's center as a fraction of the video area so the
    // placement also fits videos of other sizes. Only the vertical fraction
    // is meaningful; the horizontal center is always 0.5.
    val persistSubtitlePos = {
        if (videoSize.width > 0 && videoSize.height > 0 && subsSize.width > 0) {
            TubeApp.subtitlePlaced = true
            TubeApp.subtitleCenterX = 0.5f
            TubeApp.subtitleCenterY = subsCenterY
                ?: ((renderPos.y + subsSize.height / 2f) / videoSize.height)
        }
    }

    LaunchedEffect(currentIndex, autoFollow) {
        if (autoFollow && currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!fullscreen) TopAppBar(
                title = {
                    Column {
                        Text(
                            args.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (args.uploader.isNotBlank()) {
                            Text(
                                args.uploader + (if (args.qualityLabel.isNotBlank())
                                    " · " + args.qualityLabel else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (args.qualityNote.isNotBlank()) {
                            Text(
                                args.qualityNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share subtitles (SRT)") },
                            onClick = {
                                showMenu = false
                                shareSubtitles(context, "srt", args.videoId, track)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share subtitles (VTT)") },
                            onClick = {
                                showMenu = false
                                shareSubtitles(context, "vtt", args.videoId, track)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy transcript text") },
                            onClick = {
                                showMenu = false
                                val lines = track.cues().joinToString("\n") { it.text() }
                                clipboard.setText(AnnotatedString(lines))
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Copied ${track.cues().size} lines to clipboard"
                                    )
                                }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.padding(padding).fillMaxSize()
                )
        ) {
            // ------------------------------------------------------- video
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier.weight(1f)
                        else Modifier.height(WINDOWED_VIDEO_HEIGHT)
                    )
                    .background(Color.Black)
                    .onSizeChanged { videoSize = it }
                    .pointerInput(Unit) {
                        // Subtitle drag, living on the VIDEO BOX and running on the
                        // VIDEO BOX and running on the Initial pass. The Initial pass
                        // reaches this ancestor BEFORE the child layers below (tap
                        // surface, CC button, seek bar) process the same touch in the
                        // Main pass, so consuming here starves them — that is what lets
                        // the pill be dragged even when it sits under the progress bar.
                        // Plain taps stay unconsumed so the controls keep working
                        // through the pill (YouTube-style).
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                pass = PointerEventPass.Initial,
                                requireUnconsumed = false
                            )

                            // Is the touch inside the pill (video-local coordinates)?
                            val onPill = subsSize.width > 0 &&
                                down.position.x >= renderPos.x &&
                                down.position.x <= renderPos.x + subsSize.width &&
                                down.position.y >= renderPos.y &&
                                down.position.y <= renderPos.y + subsSize.height
                            if (!onPill) return@awaitEachGesture

                            // A plain tap stays unconsumed and falls through to
                            // the controls. A vertical move takes over as a drag;
                            // a horizontal move is not ours and falls through too.
                            var top = renderPos.y
                            var lastPos = down.position
                            var drag = false
                            val maxY = (videoSize.height - subsSize.height)
                                .coerceAtLeast(0).toFloat()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.firstOrNull { it.pressed }
                                if (pressed == null) break
                                val pos = pressed.position
                                if (!drag) {
                                    val dy = pos.y - down.position.y
                                    val dx = pos.x - down.position.x
                                    if (abs(dy) > viewConfiguration.touchSlop) {
                                        drag = true
                                        lastPos = pos
                                    } else if (abs(dx) > viewConfiguration.touchSlop) {
                                        break
                                    }
                                    continue
                                }
                                event.changes.forEach { it.consume() }
                                top = (top + (pos.y - lastPos.y))
                                    .coerceIn(0f, maxY)
                                lastPos = pos
                                subsCenterY = (top + subsSize.height / 2f) /
                                    videoSize.height
                            }
                            if (drag) persistSubtitlePos()
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            setPlayer(player)
                            // Custom controller below pops up on tap (ExoPlayer-style);
                            // the built-in one would duplicate it.
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            keepScreenOn = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Tap surface: single tap toggles the controls; double tap on
                // either half of the video seeks back/forward 10 seconds.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { offset ->
                                    // Double-tapping the subtitle restores its
                                    // default position instead of seeking.
                                    val onPill = subsSize.width > 0 &&
                                        offset.x >= renderPos.x &&
                                        offset.x <= renderPos.x + subsSize.width &&
                                        offset.y >= renderPos.y &&
                                        offset.y <= renderPos.y + subsSize.height
                                    if (onPill) {
                                        resetSubtitleDefaults()
                                    } else if (offset.x < size.width / 2f) {
                                        player.seekTo(
                                            (player.currentPosition - 10_000).coerceAtLeast(0))
                                        seekNudge = -1
                                    } else {
                                        player.seekTo(player.currentPosition + 10_000)
                                        seekNudge = 1
                                    }
                                }
                            )
                        }
                )
                // Subtitle overlay sits BELOW the controls layer: wherever the
                // user drags it, the CC button / play button / slider stay on
                // top and remain tappable (YouTube does the same).
                if (subsOn && currentIndex >= 0) {
                    // The pill is measured and placed in the SAME layout pass.
                    // The old offset + onSizeChanged round trip placed each new
                    // cue using the previous cue's size for one frame, so a cue
                    // with a different line count visibly jumped on entrance.
                    Layout(
                        content = {
                            // Reset the remembered pill size when the pill leaves
                            // composition (subs off / no cue) — otherwise the stale
                            // size keeps the drag hit-test acting on a pill that is
                            // no longer on screen.
                            DisposableEffect(Unit) {
                                onDispose { subsSize = IntSize.Zero }
                            }
                            SubtitleOverlay(
                                cue = track.cues()[currentIndex],
                                maxPillWidthPx = subtitleMaxWidthPx.roundToInt()
                                    .coerceAtMost(
                                        videoSize.width.takeIf { it > 0 } ?: Int.MAX_VALUE
                                    ),
                                modifier = Modifier
                                    // Double-tap the pill to restore the default
                                    // position. The detector must live HERE:
                                    // Material's Surface is touch-opaque for hit
                                    // testing, so taps on the pill never reach the
                                    // full-video tap surface.
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = { resetSubtitleDefaults() }
                                        )
                                    }
                                // NOTE: no drag handler here — that lives on the
                                // video Box above (Initial pass), so it wins over
                                // the seek bar / tap surface even when the pill
                                // sits under the progress bar.
                            )
                        },
                        modifier = Modifier.matchParentSize()
                    ) { measurables, constraints ->
                        val videoWidth = videoSize.width.takeIf { it > 0 }
                            ?: constraints.maxWidth
                        val videoHeight = videoSize.height.takeIf { it > 0 }
                            ?: constraints.maxHeight
                        val placeable = measurables.first().measure(
                            Constraints(
                                maxWidth = subtitleMaxWidthPx.roundToInt()
                                    .coerceIn(0, videoWidth),
                                maxHeight = constraints.maxHeight
                            )
                        )
                        val width = placeable.width
                        val height = placeable.height
                        // Default: bottom center with a constant inset (never
                        // depends on controls visibility). User-placed: keep the
                        // saved center, clamped so the pill stays on the video.
                        val insetPx = 12.dp.toPx()
                        val centerY = subsCenterY?.let { it * videoHeight }
                            ?: (videoHeight - insetPx - height / 2f)
                        val top = (centerY - height / 2f).coerceIn(
                            0f,
                            (videoHeight - height).coerceAtLeast(0).toFloat()
                        )
                        val left = (videoWidth - width) / 2f
                        val x = left.roundToInt()
                        val y = top.roundToInt()
                        val size = IntSize(width, height)
                        if (subsSize != size) subsSize = size
                        val pos = Offset(x.toFloat(), y.toFloat())
                        if (renderPos != pos) renderPos = pos
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(x, y)
                        }
                    }
                }
                if (controlsVisible) {
                    // Subtitle toggle, top-right corner.
                    IconButton(
                        onClick = { subsOn = !subsOn },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    ) {
                        Icon(
                            Icons.Default.ClosedCaption,
                            contentDescription = "Toggle subtitles",
                            tint = if (subsOn) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.75f)
                        )
                    }
                    // Big center play/pause; replay once the stream has ended.
                    val ended = durationMs > 0 && positionMs >= durationMs - 250
                    IconButton(
                        onClick = {
                            if (ended) {
                                player.seekTo(0)
                                player.play()
                            } else {
                                player.playPause()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(80.dp)
                    ) {
                        Icon(
                            when {
                                ended -> Icons.Default.Replay
                                playing -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = when {
                                ended -> "Replay"
                                playing -> "Pause"
                                else -> "Play"
                            },
                            modifier = Modifier.size(60.dp),
                            tint = Color.White
                        )
                    }
                    // Bottom bar over a scrim: 3:36 ——•—— 06:59
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                                )
                            )
                            .padding(top = 32.dp, bottom = if (fullscreen) 8.dp else 2.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                Fmt.barClock((if (seeking) seekTargetMs else positionMs) / 1000.0),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            SeekBar(
                                positionMs = (if (seeking) seekTargetMs else positionMs)
                                    .coerceIn(0L, durationMs.coerceAtLeast(0L)),
                                durationMs = durationMs.coerceAtLeast(0L),
                                onSeekPreview = {
                                    seekTargetMs = it
                                    seeking = true
                                },
                                onSeekCommit = {
                                    player.seekTo(seekTargetMs)
                                    seeking = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            )
                            Text(
                                Fmt.barClock(durationMs / 1000.0),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            // Maximize/restore is only needed when auto-rotate is
                            // off; with it on, landscape orientation drives fullscreen.
                            if (!autoRotate) {
                                IconButton(
                                    onClick = {
                                        val enter = !fullscreen
                                        manualFullscreen = enter
                                        activity?.requestedOrientation =
                                            if (enter) {
                                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                            } else {
                                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            }
                                    },
                                    modifier = Modifier
                                        .padding(start = 2.dp)
                                        .size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (fullscreen) {
                                            Icons.Default.FullscreenExit
                                        } else {
                                            Icons.Default.Fullscreen
                                        },
                                        contentDescription = if (fullscreen) {
                                            "Exit full screen"
                                        } else {
                                            "Full screen"
                                        },
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // Transient ±10 s feedback after a double-tap seek.
                if (seekNudge != 0) {
                    Box(
                        Modifier
                            .align(if (seekNudge < 0) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (seekNudge < 0) "−10 s" else "+10 s",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }

            if (!fullscreen) {
                // --------------------------------------------- transcript
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Transcript",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (autoFollow) "Following ▾" else "Follow ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (autoFollow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { autoFollow = !autoFollow }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (track.isEmpty()) {
                    Text(
                        "No speech was detected in this video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                    ) {
                        itemsIndexed(track.cues()) { index, cue ->
                            TranscriptRow(
                                index = index,
                                cue = cue,
                                active = index == currentIndex,
                                onClick = {
                                    autoFollow = false
                                    player.seekTo((cue.start() * 1000).toLong())
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    cue: SubtitleCue,
    maxPillWidthPx: Int,
    modifier: Modifier = Modifier
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color.White,
        textAlign = TextAlign.Center
    )
    val measurer = rememberTextMeasurer()
    val horizontalPaddingPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val textMaxWidthPx = (maxPillWidthPx - horizontalPaddingPx * 2).coerceAtLeast(1)
    // Compose wraps greedily, which can leave one word alone on the second
    // line. Measure first; when the cue needs a second line, replace one
    // space with an explicit newline at the most balanced word boundary.
    val displayText = remember(cue.text(), textMaxWidthPx, textStyle, measurer) {
        val natural = measurer.measure(
            text = cue.text(),
            style = textStyle,
            constraints = Constraints(maxWidth = textMaxWidthPx)
        )
        if (natural.lineCount <= 1) {
            cue.text()
        } else {
            val balanced = CueBuilder.balanceLines(cue.text())
            val balancedLayout = measurer.measure(
                text = balanced,
                style = textStyle,
                constraints = Constraints(maxWidth = textMaxWidthPx)
            )
            // On very narrow screens even a balanced split may need a third
            // line; then keep Compose's normal wrapping.
            if (balancedLayout.lineCount <= 2) balanced else cue.text()
        }
    }
    // The container must WRAP the visible pill exactly: this Box's size is what
    // the drag clamp uses. A full-width/taller container would (a) lock the
    // horizontal drag (clamped to 0) and (b) reserve dead space above the video
    // bottom, so the pill could never be dragged under the progress bar.
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                displayText,
                style = textStyle,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun TranscriptRow(
    index: Int,
    cue: SubtitleCue,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.surfaceVariant
        else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                Fmt.clock(cue.start()),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp, top = 2.dp)
            )
            Text(
                cue.text(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

private fun Player.playPause() {
    if (isPlaying) {
        pause()
    } else {
        play()
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun autoRotateEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(
        context.contentResolver,
        Settings.System.ACCELEROMETER_ROTATION,
        1
    ) == 1
}.getOrDefault(true)

/** Watches the system auto-rotate toggle so the player reacts immediately. */
@Composable
private fun rememberAutoRotateEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(autoRotateEnabled(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = autoRotateEnabled(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            observer
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}

private fun shareSubtitles(context: Context, kind: String, videoId: String, track: SubtitleTrack) {
    runCatching {
        val exports = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exports, "$videoId.$kind")
        file.writeText(if (kind == "srt") track.toSrt() else track.toVtt())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share subtitles"))
    }
}

// ------------------------------------------------------------------ seek bar
// Hand-rolled NewPipe-style bar. Track and dot are drawn on ONE canvas, so the
// dot is always dead-centered on the line — Material's Slider places its thumb
// and a custom track slot independently, which let the dot drift ~1dp off.
// Tap anywhere jumps there; dragging scrubs (preview value, commit on release).
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val dotPx = with(density) { 10.dp.toPx() }
    val strokePx = with(density) { 2.5.dp.toPx() }
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    fun targetAt(x: Float): Long {
        val f = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (f * durationMs).toLong()
    }

    Box(
        modifier
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                detectTapGestures(
                    onTap = { offset ->
                        onSeekPreview(targetAt(offset.x))
                        onSeekCommit()
                    }
                )
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset -> onSeekPreview(targetAt(offset.x)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onSeekPreview(targetAt(change.position.x))
                    },
                    onDragEnd = { onSeekCommit() },
                    onDragCancel = { onSeekCommit() }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val halfDot = dotPx / 2f
            val dotX = (size.width * fraction).coerceIn(halfDot, size.width - halfDot)
            drawLine(
                color = accent.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = accent,
                start = Offset(0f, y),
                end = Offset(dotX, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = accent,
                radius = halfDot,
                center = Offset(dotX, y)
            )
        }
    }
}
