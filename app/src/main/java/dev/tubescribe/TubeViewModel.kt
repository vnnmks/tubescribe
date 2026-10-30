package dev.tubescribe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tubescribe.media.Quality
import dev.tubescribe.net.YouTubeClient
import dev.tubescribe.transcribe.GroqKeyStore
import dev.tubescribe.transcribe.GroqModel
import dev.tubescribe.util.Cancellation
import dev.tubescribe.util.OperationCancelledException
import dev.tubescribe.util.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PipelineState {
    data object Idle : PipelineState
    data class Running(val progress: Float, val message: String) : PipelineState
    data class Done(val result: Pipeline.Result) : PipelineState
    data class Failed(val message: String) : PipelineState
}

class TubeViewModel : ViewModel() {
    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    /** URLs arriving from VIEW intents (shared/opened YouTube links). */
    private val _incomingUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val incomingUrl: SharedFlow<String> = _incomingUrl.asSharedFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    fun setUrl(value: String) {
        _url.value = value
    }

    private var cancellation: Cancellation? = null

    val running: Boolean
        get() = _state.value is PipelineState.Running

    fun submitUrl(url: String) {
        _incomingUrl.tryEmit(url)
    }

    fun start(rawUrl: String, quality: Quality, model: GroqModel) {
        val url = rawUrl.trim()
        if (url.isEmpty() || running) {
            return
        }
        val videoId = YouTubeClient.extractVideoId(url)
        if (videoId == null) {
            _state.value = PipelineState.Failed(
                "That does not look like a YouTube link.\nPaste something like " +
                    "https://youtu.be/dQw4w9WgXcQ"
            )
            return
        }
        TubeApp.lastUrl = url
        val keys = GroqKeyStore.keys()
        val cancel = Cancellation()
        cancellation = cancel
        _state.value = PipelineState.Running(0f, "Starting…")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Pipeline(TubeApp.cacheDir, keys, model).run(
                        url,
                        quality,
                        ProgressListener { fraction, message ->
                            _state.value = PipelineState.Running(
                                if (fraction < 0) -1f else fraction.toFloat(),
                                message
                            )
                        },
                        cancel
                    )
                }
                _state.value = PipelineState.Done(result)
                if (!result.fromCache()) {
                    // A fresh download grew the cache; apply the count/age
                    // limits now. The new entry is the newest one, so it is
                    // never the one trimmed. Re-runs served from the cache are
                    // skipped — nothing grew and a replayed old video must not
                    // be deleted while its player is still using the files.
                    launch(Dispatchers.IO) {
                        runCatching { CacheCleaner.clean(TubeApp.cacheDir) }
                    }
                }
            } catch (e: OperationCancelledException) {
                _state.value = PipelineState.Idle
            } catch (e: Exception) {
                _state.value = PipelineState.Failed(e.message ?: e.toString())
            } finally {
                cancellation = null
            }
        }
    }

    fun cancel() {
        cancellation?.cancel()
    }

    fun dismissResult() {
        if (_state.value is PipelineState.Done || _state.value is PipelineState.Failed) {
            _state.value = PipelineState.Idle
        }
    }

    suspend fun recent(): List<Pipeline.RecentVideo> = withContext(Dispatchers.IO) {
        runCatching { Pipeline.recent(TubeApp.cacheDir) }.getOrDefault(emptyList())
    }
}
