package dev.tubescribe.update

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES_URL =
    "https://api.github.com/repos/vnnmks/tubescribe/releases/latest"
private const val RELEASE_PAGE = "https://github.com/vnnmks/tubescribe/releases/tag/"

/** The parts of a GitHub release the update screen needs. */
data class UpdateRelease(
    val tag: String,
    val version: String,
    val pageUrl: String,
    val notes: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkBytes: Long
)

/** Result of comparing the installed app with the latest GitHub release. */
sealed interface UpdateCheck {
    data class Available(val release: UpdateRelease) : UpdateCheck
    data class UpToDate(val release: UpdateRelease) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Checks the project's public GitHub releases and downloads the attached APK.
 *
 * <p>The repository is public, so no GitHub token is needed. Unauthenticated
 * API calls are limited to 60 per hour per address, which is plenty for a
 * user-initiated check.</p>
 */
object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Blocking; call from an IO dispatcher. */
    fun check(currentVersion: String, userAgent: String): UpdateCheck {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheck.Failed(
                        "No public release was found on GitHub yet."
                    )

                    response.code == 403 || response.code == 429 -> UpdateCheck.Failed(
                        "GitHub is limiting update checks right now. Try again later."
                    )

                    !response.isSuccessful -> UpdateCheck.Failed(
                        "GitHub returned HTTP ${response.code}."
                    )

                    else -> {
                        val release = response.body?.string()?.let(::parseLatestRelease)
                        when {
                            release == null -> UpdateCheck.Failed(
                                "Could not read the latest release."
                            )

                            isNewer(release.version, currentVersion) ->
                                UpdateCheck.Available(release)

                            else -> UpdateCheck.UpToDate(release)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            UpdateCheck.Failed(
                "Couldn't reach GitHub. Check your connection and try again."
            )
        }
    }

    /**
     * Downloads [url] to [destination], resuming nothing but cleaning up a
     * partial file on failure. [onProgress] is called from the IO thread.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        userAgent: String,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("The download had no content.")
            val total = body.contentLength()
            val partial = File(destination.path + ".part")
            destination.parentFile?.mkdirs()
            partial.delete()

            try {
                partial.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (total > 0 && partial.length() != total) {
                    throw IOException("The download was incomplete. Try again.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Couldn't replace the previous download.")
                }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                destination
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
        }
    }

    /** Parses the subset of GitHub's latest-release JSON the app uses. */
    internal fun parseLatestRelease(json: String): UpdateRelease? {
        return try {
            // `object` is a Kotlin keyword, so the Java static needs backticks.
            val obj = JsonParser.`object`().from(json)
            val tag = obj.getString("tag_name", "").trim()
            if (tag.isEmpty()) {
                return null
            }
            val version = tag.removePrefix("v").removePrefix("V")

            var apkUrl: String? = null
            var apkName: String? = null
            var apkBytes = 0L
            val assets = obj.getArray("assets")
            if (assets != null) {
                for (i in 0 until assets.size) {
                    val asset = assets.getObject(i) ?: continue
                    val name = asset.getString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) {
                        continue
                    }
                    apkUrl = asset.getString("browser_download_url", null)
                    apkName = name
                    apkBytes = asset.getLong("size", 0L)
                    if (apkUrl != null) {
                        break
                    }
                }
            }

            UpdateRelease(
                tag = tag,
                version = version,
                pageUrl = obj.getString("html_url", RELEASE_PAGE + tag)
                    ?: (RELEASE_PAGE + tag),
                notes = obj.getString("body", "")?.trim() ?: "",
                apkUrl = apkUrl,
                apkName = apkName,
                apkBytes = apkBytes
            )
        } catch (e: JsonParserException) {
            null
        }
    }

    /** True when [latest] is a higher dotted version than [current]. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numericParts(latest)
        val b = numericParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) {
                return left > right
            }
        }
        return false
    }

    private fun numericParts(version: String): List<Int> {
        val core = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return core.split('.').map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
    }
}
