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
        handleIntent(intent)
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
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(viewModel::submitUrl)
        }
    }
}
