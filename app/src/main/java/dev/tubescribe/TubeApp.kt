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
    }

    var lastQuality: String
        get() = prefs.getString("quality", "P720") ?: "P720"
        set(value) {
            prefs.edit().putString("quality", value).apply()
        }

    var lastUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(value) {
            prefs.edit().putString("url", value).apply()
        }
}
