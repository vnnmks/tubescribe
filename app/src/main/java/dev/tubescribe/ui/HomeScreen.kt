package dev.tubescribe.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tubescribe.Pipeline
import dev.tubescribe.PipelineState
import dev.tubescribe.TubeApp
import dev.tubescribe.TubeViewModel
import dev.tubescribe.media.Quality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: TubeViewModel,
    onOpenKeys: () -> Unit,
    onOpenPlayer: (PlayerArgs) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val url by viewModel.url.collectAsState()
    val clipboard = LocalClipboardManager.current

    var qualityName by rememberSaveable { mutableStateOf(TubeApp.lastQuality) }
    var recent by remember { mutableStateOf<List<Pipeline.RecentVideo>>(emptyList()) }
    var showQualityMenu by remember { mutableStateOf(false) }
    val quality = Quality.fromName(qualityName, Quality.P720)

    LaunchedEffect(Unit) {
        if (url.isBlank()) {
            viewModel.setUrl(TubeApp.lastUrl)
        }
        recent = viewModel.recent()
    }

    // A finished run opens the player.
    LaunchedEffect(state) {
        val done = state as? PipelineState.Done ?: return@LaunchedEffect
        onOpenPlayer(PlayerArgs.from(done.result))
        viewModel.dismissResult()
        recent = viewModel.recent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TubeScribe", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "CLOUD · GROQ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenKeys) {
                        Icon(Icons.Default.Settings, contentDescription = "Groq API keys")
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
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = viewModel::setUrl,
                label = { Text("YouTube link") },
                placeholder = { Text("https://youtu.be/…") },
                singleLine = true,
                enabled = state !is PipelineState.Running,
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let(viewModel::setUrl)
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { showQualityMenu = true }) {
                        Text(quality.label())
                    }
                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false }
                    ) {
                        Quality.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label()) },
                                onClick = {
                                    qualityName = option.name
                                    TubeApp.lastQuality = option.name
                                    showQualityMenu = false
                                }
                            )
                        }
                    }
                }
            }

            when (val current = state) {
                is PipelineState.Running -> {
                    Column(Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { if (current.progress < 0) 0.5f else current.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                current.message,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = viewModel::cancel) {
                                Text("Cancel")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                is PipelineState.Failed -> {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            current.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                else -> Unit
            }

            val running = state is PipelineState.Running
            Button(
                onClick = { viewModel.start(url, quality) },
                enabled = !running && url.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (running) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Working…")
                } else {
                    Text("Fetch & transcribe")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Recent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            if (recent.isEmpty()) {
                Text(
                    "Videos you transcribe will show up here — the video and its " +
                        "subtitles are cached on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(recent, key = { it.id() }) { item ->
                        RecentRow(item = item, onClick = {
                            onOpenPlayer(PlayerArgs.from(item))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(item: Pipeline.RecentVideo, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SmartDisplay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.metaLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
