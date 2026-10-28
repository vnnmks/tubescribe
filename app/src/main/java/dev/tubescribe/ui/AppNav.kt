package dev.tubescribe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.tubescribe.Pipeline
import dev.tubescribe.TubeViewModel
import java.io.File

/** Where the player should open; paths only so it survives state saving. */
data class PlayerArgs(
    val mediaPath: String,
    val subsPath: String,
    val videoId: String,
    val title: String,
    val uploader: String,
    val qualityLabel: String
) {
    companion object {
        fun from(result: Pipeline.Result): PlayerArgs {
            val info = result.video()
            return PlayerArgs(
                mediaPath = result.mediaFile().absolutePath,
                subsPath = result.subtitleFile().absolutePath,
                videoId = info.id(),
                title = info.title(),
                uploader = info.uploader(),
                qualityLabel = info.qualityLabel()
            )
        }

        fun from(recent: Pipeline.RecentVideo): PlayerArgs = PlayerArgs(
            mediaPath = recent.mediaFile().absolutePath,
            subsPath = recent.subtitleFile().absolutePath,
            videoId = recent.id(),
            title = recent.title(),
            uploader = recent.uploader() ?: "",
            qualityLabel = recent.qualityLabel()
        )
    }
}

@Composable
fun AppNav(viewModel: TubeViewModel) {
    var screen by rememberSaveable { mutableStateOf("home") }
    var playerArgs by remember { mutableStateOf<PlayerArgs?>(null) }

    // URLs shared to TubeScribe (ACTION_VIEW) land here.
    LaunchedEffect(Unit) {
        viewModel.incomingUrl.collect { url ->
            viewModel.setUrl(url)
            screen = "home"
        }
    }

    when (screen) {
        "keys" -> {
            BackHandler { screen = "home" }
            KeysScreen(onBack = { screen = "home" })
        }

        "player" -> {
            val args = playerArgs
            if (args == null) {
                screen = "home"
            } else {
                BackHandler { screen = "home" }
                PlayerScreen(args = args, onBack = { screen = "home" })
            }
        }

        else -> {
            HomeScreen(
                viewModel = viewModel,
                onOpenKeys = { screen = "keys" },
                onOpenPlayer = { args ->
                    playerArgs = args
                    screen = "player"
                }
            )
        }
    }
}

@Composable
fun rememberMediaFile(path: String): File = remember(path) { File(path) }
