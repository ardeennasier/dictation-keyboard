package com.dictation.keyboard.asr

import android.os.Process
import com.dictation.keyboard.model.ModelStore
import com.dictation.keyboard.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface EngineState {
    data object NotLoaded : EngineState
    data object NoModel : EngineState
    data class Loading(val modelName: String) : EngineState
    data class Ready(val modelName: String, val loadMs: Long) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Owns the one loaded speech model for the whole process (keyboard and app share it), and the
 * single background thread every recogniser call runs on. Nothing here touches the main thread.
 */
class EngineHolder(
    private val store: ModelStore,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            r.run()
        }, "asr")
    }.asCoroutineDispatcher()

    private val mutex = Mutex()
    private var engine: SpeechEngine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state

    private val numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Returns the selected model, loading it first if needed. Null if no model is installed. */
    suspend fun get(): SpeechEngine? = mutex.withLock {
        val wanted = store.find(settings.selectedModelId.value)
        if (wanted == null) {
            withContext(dispatcher) { engine?.release(); engine = null }
            _state.value = EngineState.NoModel
            return null
        }
        engine?.let { if (it.model.id == wanted.id) return it }
        withContext(dispatcher) {
            engine?.release()
            engine = null
            _state.value = EngineState.Loading(wanted.name)
            val t0 = System.nanoTime()
            try {
                SpeechEngine.load(wanted, numThreads).also {
                    engine = it
                    _state.value = EngineState.Ready(wanted.name, (System.nanoTime() - t0) / 1_000_000)
                }
            } catch (t: Throwable) {
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                null
            }
        }
    }

    fun preload() {
        scope.launch { get() }
    }

    /** Frees the model (e.g. before replacing its files, or when memory is critically low). */
    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) {
            engine?.release()
            engine = null
        }
        _state.value = EngineState.NotLoaded
    }

    fun unloadAsync() {
        scope.launch { unload() }
    }
}
