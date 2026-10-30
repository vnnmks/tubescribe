package dev.tubescribe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tubescribe.update.ApkInstaller
import dev.tubescribe.update.UpdateCheck
import dev.tubescribe.update.UpdateChecker
import dev.tubescribe.update.UpdateRelease
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings -> App updates: compares the installed version with the latest
 * release on GitHub and, when one exists, downloads the attached APK and hands
 * it to Android's package installer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember(context) { appVersion(context) }
    val userAgent = remember(version) { "TubeScribe/$version (Android)" }

    var checking by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<UpdateCheck?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun rememberExistingApk(release: UpdateRelease) {
        val expected = downloadTarget(context, release)
        apkFile = expected.takeIf { it.isFile && it.length() > 0 }
    }

    suspend fun runCheck() {
        checking = true
        message = null
        val fresh = withContext(Dispatchers.IO) {
            UpdateChecker.check(version, userAgent)
        }
        result = fresh
        if (fresh is UpdateCheck.Available) {
            rememberExistingApk(fresh.release)
        }
        checking = false
    }

    fun installDownloaded(file: File) {
        if (!ApkInstaller.canInstall(context)) {
            message = "Allow TubeScribe to install unknown apps, then tap Install update."
            runCatching { ApkInstaller.openPermissionSettings(context) }
                .onFailure { message = "Couldn't open the install permission settings." }
            return
        }
        runCatching { ApkInstaller.install(context, file) }
            .onFailure {
                message = "Couldn't open Android's installer. The APK is in the app cache."
            }
    }

    fun downloadAndInstall(release: UpdateRelease) {
        val url = release.apkUrl ?: return
        if (downloading) {
            return
        }
        message = null
        downloading = true
        downloadedBytes = 0
        totalBytes = release.apkBytes
        scope.launch {
            try {
                val file = UpdateChecker.downloadApk(
                    url = url,
                    destination = downloadTarget(context, release),
                    userAgent = userAgent
                ) { done, total ->
                    withContext(Dispatchers.Main) {
                        downloadedBytes = done
                        if (total > 0) {
                            totalBytes = total
                        }
                    }
                }
                apkFile = file
                downloading = false
                installDownloaded(file)
            } catch (e: CancellationException) {
                downloading = false
                throw e
            } catch (e: Exception) {
                downloading = false
                message = e.message ?: "The download failed. Try again."
            }
        }
    }

    LaunchedEffect(Unit) {
        runCheck()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App updates") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("TubeScribe $version", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Checks the latest release on GitHub",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when {
                checking -> CheckingCard()

                result is UpdateCheck.Available -> {
                    val release = (result as UpdateCheck.Available).release
                    UpdateCard(
                        currentVersion = version,
                        release = release,
                        downloading = downloading,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        hasDownload = apkFile?.isFile == true,
                        onPrimary = {
                            val file = apkFile
                            if (file != null && file.isFile) {
                                installDownloaded(file)
                            } else {
                                downloadAndInstall(release)
                            }
                        },
                        onOpenPage = { openUrl(context, release.pageUrl) }
                    )
                }

                result is UpdateCheck.UpToDate -> {
                    val release = (result as UpdateCheck.UpToDate).release
                    UpToDateCard(
                        latestTag = release.tag,
                        checking = checking,
                        onCheckAgain = { scope.launch { runCheck() } }
                    )
                }

                result is UpdateCheck.Failed -> {
                    val failure = result as UpdateCheck.Failed
                    FailedCard(
                        message = failure.message,
                        onRetry = { scope.launch { runCheck() } }
                    )
                }
            }

            message?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Text(
                "Updates come from the APK attached to the latest GitHub release. " +
                    "Android always asks you to confirm the install; the first time, " +
                    "allow TubeScribe to install unknown apps. Cached videos and " +
                    "settings are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
            )

            TextButton(
                onClick = {
                    openUrl(context, "https://github.com/vnnmks/tubescribe/releases")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open GitHub releases")
            }
        }
    }
}

@Composable
private fun CheckingCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text("Checking GitHub for the latest release…")
        }
    }
}

@Composable
private fun UpToDateCard(
    latestTag: String,
    checking: Boolean,
    onCheckAgain: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("You're up to date", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The latest release is $latestTag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCheckAgain, enabled = !checking) {
                Text("Check again")
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("Couldn't check for updates", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun UpdateCard(
    currentVersion: String,
    release: UpdateRelease,
    downloading: Boolean,
    downloadedBytes: Long,
    totalBytes: Long,
    hasDownload: Boolean,
    onPrimary: () -> Unit,
    onOpenPage: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Version ${release.version} is available",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "You have $currentVersion.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (release.apkUrl == null) {
                Text(
                    "This release has no APK attached. Open the release page to " +
                        "download it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenPage, modifier = Modifier.fillMaxWidth()) {
                    Text("Open release page")
                }
            } else {
                if (downloading) {
                    val fraction = if (totalBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    if (totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (totalBytes > 0) {
                            "Downloading… ${(fraction * 100).toInt()}% of " +
                                formatBytes(totalBytes)
                        } else {
                            "Downloading…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = onPrimary,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            downloading -> "Downloading…"
                            hasDownload -> "Install update"
                            else -> "Download & install"
                        }
                    )
                }
                if (!hasDownload && release.apkBytes > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "APK · ${formatBytes(release.apkBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenPage) {
                Text("View release on GitHub")
            }
        }
    }
}

private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

private fun downloadTarget(context: Context, release: UpdateRelease): File {
    val safeVersion = release.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    return File(dir, "TubeScribe-$safeVersion.apk")
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
