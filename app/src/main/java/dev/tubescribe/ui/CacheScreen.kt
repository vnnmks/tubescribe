package dev.tubescribe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tubescribe.CacheCleaner
import dev.tubescribe.TubeApp
import dev.tubescribe.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Caching videos: how long downloaded videos are kept on disk and
 * how many of them at most. Changing either immediately prunes the cache with
 * the new limits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheScreen(onBack: () -> Unit) {
    var retentionMs by rememberSaveable { mutableStateOf(TubeApp.cacheRetentionMs) }
    var maxVideos by rememberSaveable { mutableStateOf(TubeApp.cacheMaxVideos) }
    var operator by rememberSaveable { mutableStateOf(TubeApp.cacheOperator) }
    var retentionMenu by remember { mutableStateOf(false) }
    var countMenu by remember { mutableStateOf(false) }
    var operatorMenu by remember { mutableStateOf(false) }
    var showCountInput by rememberSaveable { mutableStateOf(false) }
    var countDraft by rememberSaveable { mutableStateOf("") }
    var countError by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf<CacheCleaner.DiskUsage?>(null) }
    val scope = rememberCoroutineScope()

    fun persistAndClean() {
        scope.launch(Dispatchers.IO) {
            runCatching { CacheCleaner.clean(TubeApp.cacheDir) }
            val fresh = runCatching { CacheCleaner.usage(TubeApp.cacheDir) }.getOrNull()
            withContext(Dispatchers.Main) { usage = fresh }
        }
    }

    fun applyCount() {
        val parsed = countDraft.trim().toIntOrNull()
        if (parsed == null || parsed < 1 || parsed > CacheCleaner.MAX_CUSTOM_COUNT) {
            countError = "Enter a number from 1 to ${CacheCleaner.MAX_CUSTOM_COUNT}."
            return
        }
        maxVideos = parsed
        TubeApp.cacheMaxVideos = parsed
        showCountInput = false
        countDraft = ""
        countError = null
        persistAndClean()
    }

    // Snapshot what the cache occupies on disk the moment the screen opens.
    LaunchedEffect(Unit) {
        usage = withContext(Dispatchers.IO) {
            runCatching { CacheCleaner.usage(TubeApp.cacheDir) }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Caching videos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Downloaded videos are kept on this device so re-runs play " +
                    "instantly and offline. Depending on the rule below, entries " +
                    "are deleted when they get too old, when there are more of " +
                    "them than the count allows, or once both — checked at app " +
                    "start and after each new transcription.\n\nVideos starred " +
                    "in the Cached list are stored permanently and never count " +
                    "against the limits. Un-starring puts them back on top of " +
                    "the cache with a fresh age.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            DropdownRow(
                label = "Keep videos for",
                subtitle = "Deletes downloads older than this",
                value = CacheCleaner.retentionLabel(retentionMs),
                expanded = retentionMenu,
                onExpandedChange = { retentionMenu = it }
            ) {
                CacheCleaner.retentionValues.forEach { option ->
                    CenteredItem(
                        text = CacheCleaner.retentionLabel(option),
                        onClick = {
                            retentionMs = option
                            TubeApp.cacheRetentionMs = option
                            retentionMenu = false
                            persistAndClean()
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            DropdownRow(
                label = "Keep at most",
                subtitle = "Newest videos kept, oldest removed first",
                value = CacheCleaner.countLabel(maxVideos),
                expanded = countMenu,
                onExpandedChange = {
                    if (!it) showCountInput = false
                    countMenu = it
                }
            ) {
                CacheCleaner.countPresets.forEach { option ->
                    CenteredItem(
                        text = CacheCleaner.countLabel(option),
                        onClick = {
                            maxVideos = option
                            TubeApp.cacheMaxVideos = option
                            countMenu = false
                            showCountInput = false
                            countError = null
                            persistAndClean()
                        }
                    )
                }
                CenteredItem(
                    text = "Custom…",
                    onClick = {
                        countMenu = false
                        showCountInput = true
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            DropdownRow(
                label = "Delete videos when",
                subtitle = "How the two limits combine",
                value = CacheCleaner.operatorLabel(operator),
                expanded = operatorMenu,
                onExpandedChange = { operatorMenu = it }
            ) {
                listOf("or", "and").forEach { option ->
                    CenteredItem(
                        text = CacheCleaner.operatorLabel(option),
                        onClick = {
                            operator = option
                            TubeApp.cacheOperator = option
                            operatorMenu = false
                            persistAndClean()
                        }
                    )
                }
            }

            if (showCountInput) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = countDraft,
                        onValueChange = {
                            countDraft = it
                            countError = null
                        },
                        label = { Text("Number of videos (1–${CacheCleaner.MAX_CUSTOM_COUNT})") },
                        isError = countError != null,
                        supportingText = { countError?.let { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        keyboardActions = KeyboardActions(onDone = { applyCount() }),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { applyCount() },
                        enabled = countDraft.isNotBlank()
                    ) {
                        Text("Apply")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Disk used by cache", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        usage?.let(::usageLine) ?: "Measuring…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** "412.3 MB · 2 videos (of which saved: 210 MB)"; empty cache reads plainly. */
private fun usageLine(usage: CacheCleaner.DiskUsage): String {
    if (usage.entries == 0) {
        return "Nothing cached yet"
    }
    return buildString {
        append(Fmt.bytes(usage.totalBytes))
        append(" · ")
        append(usage.entries).append(if (usage.entries == 1) " video" else " videos")
        if (usage.savedBytes > 0) {
            append(" (of which saved: ").append(Fmt.bytes(usage.savedBytes)).append(')')
        }
    }
}

/** A settings row whose value is a dropdown selection. */
@Composable
private fun DropdownRow(
    label: String,
    subtitle: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    // The menu spans the row's full width with centered items, so it is sized
    // by the measured row rather than the text of its longest entry.
    var rowWidthPx by remember { mutableStateOf(0) }
    val menuWidth = with(LocalDensity.current) { rowWidthPx.toDp() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width }
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(true) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.width(menuWidth)
            ) {
                content()
            }
        }
    }
}

/** DropdownMenuItem whose label spans the menu width, centered. */
@Composable
private fun CenteredItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        onClick = onClick
    )
}
