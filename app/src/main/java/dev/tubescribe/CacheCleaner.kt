package dev.tubescribe

import java.io.File
import java.util.logging.Logger

/**
 * Applies the caching limits configured under Settings → Caching videos to the
 * per-video disk cache (see [TubeApp.cacheDir] and [Pipeline.run]):
 *
 *  - the age limit ([TubeApp.cacheRetentionMs], 0 = no limit) deletes entries
 *    that were last downloaded more than that long ago,
 *  - the count limit ([TubeApp.cacheMaxVideos], -1 = no limit) keeps the N
 *    newest entries and removes the rest (least recently downloaded first),
 *  - and [TubeApp.cacheOperator] combines the two: "or" deletes an entry once
 *    either limit is exceeded, "and" only once both are. If only one limit is
 *    configured, it applies alone. Default is "or".
 *
 * Starred (saved) videos ([TubeApp.savedVideoIds]) are exempt from every
 * limit and do not consume a slot in the count limit; they stay cached until
 * the user un-flags them. Un-flagging bumps the entry's timestamps via
 * [markFresh], which puts it at the top of the cache and restarts its age.
 *
 * An entry's age is the newest file mtime inside its folder, i.e. when it was
 * last downloaded; loading cached content does not refresh the age.
 */
object CacheCleaner {
    private val LOG = Logger.getLogger(CacheCleaner::class.java.name)

    /** "Keep videos for" choices shown in Settings, in ms; 0 = "No limit". */
    val retentionValues = longArrayOf(
        1L * 60 * 60 * 1000,        // 1 hour
        6L * 60 * 60 * 1000,        // 6 hours
        24L * 60 * 60 * 1000,       // 1 day
        3L * 24 * 60 * 60 * 1000,   // 3 days
        7L * 24 * 60 * 60 * 1000,   // 1 week
        0L,                         // no limit
    )

    /** "Keep at most" presets shown in Settings; -1 = "No limit". */
    val countPresets = intArrayOf(-1, 3, 5, 10, 20)

    /** Largest number of cached videos the custom input accepts. */
    const val MAX_CUSTOM_COUNT = 100

    /** Disk usage snapshot of the video cache, for the settings screen. */
    data class DiskUsage(val totalBytes: Long, val savedBytes: Long, val entries: Int)

    /** Sums up what each cached video occupies on disk right now. */
    fun usage(cacheRoot: File): DiskUsage {
        var total = 0L
        var saved = 0L
        var entries = 0
        val savedIds = TubeApp.savedVideoIds
        cacheRoot.listFiles(File::isDirectory)?.forEach { dir ->
            entries++
            val bytes = dir.listFiles()
                ?.sumOf { file -> if (file.isFile) file.length() else 0L } ?: 0L
            total += bytes
            if (savedIds.contains(dir.name)) {
                saved += bytes
            }
        }
        return DiskUsage(total, saved, entries)
    }

    fun retentionLabel(ms: Long): String = when (ms) {
        0L -> "No limit"
        1L * 60 * 60 * 1000 -> "1 hour"
        6L * 60 * 60 * 1000 -> "6 hours"
        24L * 60 * 60 * 1000 -> "1 day"
        3L * 24 * 60 * 60 * 1000 -> "3 days"
        7L * 24 * 60 * 60 * 1000 -> "1 week"
        else -> "$ms ms"
    }

    fun countLabel(count: Int): String =
        if (count < 0) "No limit"
        else count.toString() + " video" + (if (count == 1) "" else "s")

    fun operatorLabel(operator: String): String =
        if (operatorIs(operator, "or")) "Either one is hit" else "Both are hit"

    private fun operatorIs(value: String, expected: String) =
        value.equals(expected, ignoreCase = true)

    /**
     * Deletes cache entries that exceed the configured age and count limits.
     * Runs on the calling thread, so callers should dispatch it off the UI
     * thread.
     */
    @Synchronized
    fun clean(cacheRoot: File) {
        val retentionMs = TubeApp.cacheRetentionMs
        val maxVideos = TubeApp.cacheMaxVideos
        if (retentionMs <= 0 && maxVideos < 0) {
            return
        }

        // Saved entries are exempt from every limit and occupy no count slot.
        val saved = TubeApp.savedVideoIds
        val dirs = (cacheRoot.listFiles(File::isDirectory) ?: return)
            .filterNot { saved.contains(it.name) }
        if (dirs.isEmpty()) {
            return
        }
        val newestFirst = dirs
            .map { it to newestMtime(it) }
            .sortedByDescending { it.second }

        val bothLimits = retentionMs > 0 && maxVideos >= 0
        val orOperator = operatorIs(TubeApp.cacheOperator, "or")
        val cutoff = if (retentionMs > 0) System.currentTimeMillis() - retentionMs else 0L

        var removed = 0
        for ((index, entry) in newestFirst.withIndex()) {
            val expired = retentionMs > 0 && entry.second < cutoff
            val overCount = maxVideos >= 0 && index >= maxVideos
            val shouldRemove = when {
                !bothLimits -> expired || overCount
                orOperator -> expired || overCount
                else -> expired && overCount
            }
            if (shouldRemove && deleteEntry(entry.first)) {
                removed++
            }
        }

        if (removed > 0) {
            LOG.info("Cache cleanup removed " + removed + " cached entries"
                    + " (retentionMs=" + retentionMs + ", maxVideos=" + maxVideos
                    + ", operator=" + TubeApp.cacheOperator + ")")
        }
    }

    /**
     * Gives a cache entry a fresh "just downloaded" timestamp: it moves to the
     * top of the cache (newest first) and its age limit starts over. Used when
     * a saved video is un-flagged in the home screen's Cached list.
     */
    fun markFresh(cacheRoot: File, id: String): Boolean {
        val dir = File(cacheRoot, id)
        if (!dir.isDirectory) {
            return false
        }
        val now = System.currentTimeMillis()
        var ok = dir.setLastModified(now)
        dir.listFiles()?.forEach { file ->
            ok = file.setLastModified(now) && ok
        }
        return ok
    }

    private fun newestMtime(dir: File): Long =
        dir.listFiles()
            ?.mapNotNull { if (it.isFile) it.lastModified() else null }
            ?.maxOrNull()
            ?: 0L

    /** @return whether the entry disappeared (it may already be gone). */
    private fun deleteEntry(dir: File): Boolean {
        val gone = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (!gone) {
            LOG.warning("Could not delete cached entry " + dir)
        }
        return gone
    }
}
