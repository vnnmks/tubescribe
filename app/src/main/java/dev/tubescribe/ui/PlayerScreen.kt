package dev.tubescribe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.tubescribe.subs.SubtitleCue
import dev.tubescribe.subs.SubtitleTrack
import dev.tubescribe.util.Fmt
import java.io.File
import kotlinx.coroutines.delay

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
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            playing = player.isPlaying
            delay(120)
        }
    }

    val positionSec = positionMs / 1000.0
    val currentIndex = track.indexAt(positionSec)

    var subsOn by rememberSaveable { mutableStateOf(true) }
    var autoFollow by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex, autoFollow) {
        if (autoFollow && currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
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
                                clipboard.setText(AnnotatedString(
                                    track.cues().joinToString("\n") { it.text() }
                                ))
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
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            keepScreenOn = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
                if (subsOn && currentIndex >= 0) {
                    SubtitleOverlay(cue = track.cues()[currentIndex])
                }
            }

            // -------------------------------------------------- transport
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds")
                }
                IconButton(onClick = { player.playPause() }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(38.dp)
                    )
                }
                IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds")
                }
                IconButton(onClick = { subsOn = !subsOn }) {
                    Icon(
                        Icons.Default.ClosedCaption,
                        contentDescription = "Toggle subtitles",
                        tint = if (subsOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Fmt.clock(positionSec) + " / " + Fmt.clock(player.duration.coerceAtLeast(0) / 1000.0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ------------------------------------------------- transcript
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 52.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp)
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
