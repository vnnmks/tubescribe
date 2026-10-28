package dev.tubescribe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.tubescribe.TubeApp
import dev.tubescribe.subs.SubtitleCue
import dev.tubescribe.subs.SubtitleTrack
import dev.tubescribe.util.Fmt
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(args: PlayerArgs, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(args.mediaPath))))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
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
    // User-placed position (top-left, px) and whether the user placed it at all.
    var subsPlaced by remember { mutableStateOf(TubeApp.subtitlePlaced) }
    var placedPos by remember { mutableStateOf(Offset.Zero) }
    // Effective rendered position; always kept inside the video box.
    var renderPos by remember { mutableStateOf(Offset.Zero) }
    var overlayMeasured by remember { mutableStateOf(false) }
    var restoredOnce by remember { mutableStateOf(false) }

    // Compute the rendered position every frame: default (bottom center) until the
    // user drags; afterwards the placed position, clamped to stay on the video.
    // The inset is CONSTANT — it must never depend on controls visibility, or the
    // subtitle would jump every time the progress bar opens/hides. When the bar
    // overlaps the subtitle, the bar simply draws on top of it.
    if (videoSize.width > 0 && videoSize.height > 0 && subsSize.width > 0) {
        val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            12.dp.toPx()
        }
        val target =
            if (subsPlaced && restoredOnce) {
                placedPos
            } else {
                Offset(
                    (videoSize.width - subsSize.width) / 2f,
                    (videoSize.height - subsSize.height) - insetPx
                )
            }
        val clamped = Offset(
            target.x.coerceIn(0f, (videoSize.width - subsSize.width).coerceAtLeast(0).toFloat()),
            target.y.coerceIn(0f, (videoSize.height - subsSize.height).coerceAtLeast(0).toFloat())
        )
        if (clamped != renderPos) {
            renderPos = clamped
        }
        if (!overlayMeasured) {
            overlayMeasured = true
        }
    }

    // Restore the saved position once sizes are known.
    LaunchedEffect(videoSize, subsSize) {
        if (!overlayMeasured || restoredOnce || !subsPlaced) return@LaunchedEffect
        restoredOnce = true
        placedPos = Offset(
            TubeApp.subtitleCenterX * videoSize.width - subsSize.width / 2f,
            TubeApp.subtitleCenterY * videoSize.height - subsSize.height / 2f
        )
    }

    // Persist the overlay's center as a fraction of the video area so the
    // placement also fits videos of other sizes.
    val persistSubtitlePos = {
        if (videoSize.width > 0 && videoSize.height > 0 && subsSize.width > 0) {
            TubeApp.subtitlePlaced = true
            TubeApp.subtitleCenterX =
                (renderPos.x + subsSize.width / 2f) / videoSize.width
            TubeApp.subtitleCenterY =
                (renderPos.y + subsSize.height / 2f) / videoSize.height
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
            TopAppBar(
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
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ------------------------------------------------------- video
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color.Black)
                    .onSizeChanged { videoSize = it }
                    .pointerInput(Unit) {
                        // Subtitle drag + long-press reset, living on the VIDEO BOX and
                        // running on the Initial pass. The Initial pass reaches this
                        // ancestor BEFORE the child layers below (tap surface, CC
                        // button, seek bar) process the same touch in the Main pass,
                        // so consuming here starves them — that is what lets the pill
                        // be dragged even when it sits under the progress bar.
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
                            var start = down.position
                            var lastPos = start
                            var drag = false
                            var releasedEarly = false
                            // Decide inside the long-press window: released early ->
                            // plain tap (fall through to controls); moved beyond slop ->
                            // drag; window expired -> long press.
                            val decision = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis
                            ) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.firstOrNull { it.pressed }
                                    if (pressed == null) {
                                        releasedEarly = true
                                        break
                                    }
                                    val pos = pressed.position
                                    if ((pos - start).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        drag = true
                                        lastPos = pos
                                        break
                                    }
                                }
                            }
                            when {
                                drag -> {
                                    // Pill drag: pick up from wherever it renders and
                                    // claim the whole gesture.
                                    subsPlaced = true
                                    restoredOnce = true
                                    placedPos = renderPos
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                        val pressed = event.changes.firstOrNull { it.pressed }
                                        if (pressed == null) break
                                        val pos = pressed.position
                                        val maxX = (videoSize.width - subsSize.width)
                                            .coerceAtLeast(0).toFloat()
                                        val maxY = (videoSize.height - subsSize.height)
                                            .coerceAtLeast(0).toFloat()
                                        placedPos = Offset(
                                            (placedPos.x + (pos.x - lastPos.x))
                                                .coerceIn(0f, maxX),
                                            (placedPos.y + (pos.y - lastPos.y))
                                                .coerceIn(0f, maxY)
                                        )
                                        lastPos = pos
                                        renderPos = placedPos
                                    }
                                    persistSubtitlePos()
                                }
                                decision == null && !releasedEarly -> {
                                    // Long press: reset to the default bottom-center spot
                                    // and swallow the rest of the gesture so no tap
                                    // leaks to the controls below.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                    subsPlaced = false
                                    TubeApp.subtitlePlaced = false
                                }
                            }
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
                                    if (offset.x < size.width / 2f) {
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
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(renderPos.x.roundToInt(), renderPos.y.roundToInt())
                            }
                            .onSizeChanged { subsSize = it }
                            // NOTE: no pointerInput here — the pill's drag/long-press
                            // handling lives on the video Box above (Initial pass), so
                            // it wins over the seek bar / tap surface even when the pill
                            // sits under the progress bar.
                    ) {
                        // Reset the remembered pill size when the pill leaves
                        // composition (between cues) — otherwise the stale size
                        // keeps the positioning block and the drag hit-test acting
                        // on a pill that is no longer on screen.
                        DisposableEffect(Unit) {
                            onDispose { subsSize = IntSize.Zero }
                        }
                        SubtitleOverlay(cue = track.cues()[currentIndex])
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
                            .padding(top = 32.dp, bottom = 2.dp)
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

            // ------------------------------------------------- transcript
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

@Composable
private fun SubtitleOverlay(cue: SubtitleCue) {
    // The container must WRAP the visible pill exactly: this Box's size is what
    // the drag clamp uses. A full-width/taller container would (a) lock the
    // horizontal drag (clamped to 0) and (b) reserve dead space above the video
    // bottom, so the pill could never be dragged under the progress bar.
    Box(contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                cue.text(),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
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
                color = Color.Red.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.Red,
                start = Offset(0f, y),
                end = Offset(dotX, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = Color.Red,
                radius = halfDot,
                center = Offset(dotX, y)
            )
        }
    }
}
