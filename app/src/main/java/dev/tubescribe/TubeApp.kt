package dev.tubescribe

import android.content.Context
import android.content.SharedPreferences
import dev.tubescribe.transcribe.GroqKeyStore
import java.io.File

/** Process-wide Android paths and preferences, initialized from the activity. */
object TubeApp {
    lateinit var baseDir: File
        private set
    lateinit var cacheDir: File
        private set
    lateinit var prefs: SharedPreferences
        private set

    fun init(context: Context) {
        if (::baseDir.isInitialized) {
            return
        }
        val app = context.applicationContext
        baseDir = app.filesDir
        cacheDir = File(baseDir, "cache").apply { mkdirs() }
        GroqKeyStore.init(baseDir)
        prefs = app.getSharedPreferences("tubescribe", Context.MODE_PRIVATE)
        installLogcatBridge()
    }

    /** Routes java.util.logging (used by the ported pipeline) into logcat. */
    private fun installLogcatBridge() {
        val root = java.util.logging.Logger.getLogger("")
        if (root.handlers.any { it is LogcatHandler }) {
            return
        }
        root.addHandler(LogcatHandler())
    }

    private class LogcatHandler : java.util.logging.Handler() {
        override fun publish(record: java.util.logging.LogRecord) {
            val tag = "TubeScribe/" + record.loggerName.substringAfterLast('.')
            val message = buildString {
                append(record.message ?: return)
                record.thrown?.let {
                    append('\n')
                    append(android.util.Log.getStackTraceString(it))
                }
            }
            val priority = when {
                record.level.intValue() >= java.util.logging.Level.SEVERE.intValue() ->
                    android.util.Log.ERROR
                record.level.intValue() >= java.util.logging.Level.WARNING.intValue() ->
                    android.util.Log.WARN
                record.level.intValue() >= java.util.logging.Level.INFO.intValue() ->
                    android.util.Log.INFO
                else -> android.util.Log.DEBUG
            }
            android.util.Log.println(priority, tag, message)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    var lastQuality: String
        get() = prefs.getString("quality", "P720") ?: "P720"
        set(value) {
            prefs.edit().putString("quality", value).apply()
        }

    /** Home screen's Whisper model choice, stored as a [GroqModel] enum name. */
    var lastWhisperModel: String
        get() = prefs.getString("whisper_model", "TURBO") ?: "TURBO"
        set(value) {
            prefs.edit().putString("whisper_model", value).apply()
        }

    var lastUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(value) {
            prefs.edit().putString("url", value).apply()
        }

    /** Whether the user has manually placed the subtitle overlay on the video. */
    var subtitlePlaced: Boolean
        get() = prefs.getBoolean("subs_placed", false)
        set(value) {
            prefs.edit().putBoolean("subs_placed", value).apply()
        }

    /** Subtitle overlay center as a fraction of the video area (survives restarts). */
    var subtitleCenterX: Float
        get() = prefs.getFloat("subs_cx", 0.5f)
        set(value) {
            prefs.edit().putFloat("subs_cx", value).apply()
        }

    var subtitleCenterY: Float
        get() = prefs.getFloat("subs_cy", 0.85f)
        set(value) {
            prefs.edit().putFloat("subs_cy", value).apply()
        }

    /** Settings → Caching videos: how long cached videos may live, in ms (0 = no limit). */
    var cacheRetentionMs: Long
        get() = prefs.getLong("cache_retention_ms", 7L * 24 * 60 * 60 * 1000)
        set(value) {
            prefs.edit().putLong("cache_retention_ms", value).apply()
        }

    /** Settings → Caching videos: how many videos to keep cached (-1 = no limit). */
    var cacheMaxVideos: Int
        get() = prefs.getInt("cache_max_videos", 3)
        set(value) {
            prefs.edit().putInt("cache_max_videos", value).apply()
        }

    /**
     * How the two caching limits combine: "or" deletes an entry once either
     * limit is exceeded, "and" once both are exceeded. When only one limit is
     * configured, it applies alone regardless of the operator.
     */
    var cacheOperator: String
        get() = prefs.getString("cache_operator", "or") ?: "or"
        set(value) {
            prefs.edit().putString("cache_operator", value).apply()
        }

    /** Video IDs the user starred; exempt from every cache limit until un-starred. */
    var savedVideoIds: Set<String>
        get() = prefs.getStringSet("saved_ids", emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("saved_ids", value).apply()
        }

    fun isSaved(id: String): Boolean = savedVideoIds.contains(id)

    fun saveVideo(id: String) {
        savedVideoIds = savedVideoIds + id
    }

    fun unsaveVideo(id: String) {
        savedVideoIds = savedVideoIds - id
    }

    /**
     * Debug-only override for the audio chunk byte limit (0 = production
     * limit). A small forced limit makes [dev.tubescribe.media.AudioChunker]
     * split even short audio into many chunks, so seam handling can be
     * exercised on a short test video.
     */
    @JvmStatic
    fun debugChunkLimit(): Long = prefs.getLong("debug_chunk_bytes", 0L)
}
