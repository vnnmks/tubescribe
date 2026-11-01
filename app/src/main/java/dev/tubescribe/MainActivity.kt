package dev.tubescribe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.tubescribe.ui.AppNav
import dev.tubescribe.ui.TubeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TubeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TubeApp.init(this)
        // Settings → Caching videos: prune the cache once at process start.
        Thread { runCatching { CacheCleaner.clean(TubeApp.cacheDir) } }.start()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            TubeTheme {
                AppNav(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::extractLink)
            else -> null
        }
        url?.let(viewModel::submitUrl)
    }

    // Shared text can be "Title https://youtu.be/..."; keep just the link.
    private fun extractLink(text: String): String =
        Regex("https?://\\S+").find(text)?.value ?: text.trim()
}
