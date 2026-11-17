package com.dictation.keyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.dictation.keyboard.app
import com.dictation.keyboard.asr.DictationSession
import com.dictation.keyboard.asr.EngineState
import com.dictation.keyboard.audio.MicRecorder
import com.dictation.keyboard.metrics.MetricsStore
import com.dictation.keyboard.metrics.SessionMetrics
import com.dictation.keyboard.text.InsertionFormatter
import com.dictation.keyboard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

sealed interface KbStatus {
    data object Idle : KbStatus
    data object Recording : KbStatus
    data object Finishing : KbStatus
    data class Message(val text: String, val opensApp: Boolean = false) : KbStatus
}

/**
 * Keyboard logic, kept separate from the Compose UI. Lives on the main thread; all audio and
 * recognition work happens on background threads inside [MicRecorder] and [DictationSession].
 */
class KeyboardController(
    private val service: DictationImeService,
    private val scope: CoroutineScope, // main-thread scope tied to the service
) {
    private val app = service.app

    private val _status = MutableStateFlow<KbStatus>(KbStatus.Idle)
    val status: StateFlow<KbStatus> = _status

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    private val _levels = MutableStateFlow(List(WAVE_BARS) { 0f })
    val levels: StateFlow<List<Float>> = _levels

    /** Text from a dictation that couldn't be inserted (keyboard closed mid-way). */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    val engineState: StateFlow<EngineState> get() = app.engine.state

    private var recorder: MicRecorder? = null
    private var session: DictationSession? = null
    private var probe: MetricsStore.Probe? = null
    private var liveJob: Job? = null

    // Audio that arrives while the model is still loading is kept and fed in once it's ready,
    // so you can start speaking the moment you tap.
    private val lock = Any()
    private val earlyAudio = ArrayList<FloatArray>()
    private var target: DictationSession? = null

    /** Bumped on every start/abort so stale background work can tell it's out of date. */
    private var token = 0

    val isBusy: Boolean get() = _status.value == KbStatus.Recording || _status.value == KbStatus.Finishing

    fun onShown() {
        if (_status.value is KbStatus.Message) _status.value = KbStatus.Idle
        if (app.models.installed().isNotEmpty()) app.engine.preload()
    }

    fun onHidden() {
        // Keyboard closed while recording: finish, but don't insert into whatever field comes next.
        if (_status.value == KbStatus.Recording) stop(insert = false)
    }

    fun onMicTap() {
        when (_status.value) {
            KbStatus.Idle, is KbStatus.Message -> start()
            KbStatus.Recording -> stop(insert = true)
            KbStatus.Finishing -> Unit
        }
    }

    fun onMessageTap() {
        val s = _status.value
        if (s is KbStatus.Message && s.opensApp) openApp()
        _status.value = KbStatus.Idle
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.value = KbStatus.Message("Microphone permission needed. Tap here to open the app.", opensApp = true)
            return
        }
        if (app.models.installed().isEmpty()) {
            _status.value = KbStatus.Message("No speech model installed yet. Tap here to open the app.", opensApp = true)
            return
        }
        _pending.value = null
        synchronized(lock) { earlyAudio.clear(); target = null }
        val rec = MicRecorder { chunk -> onAudio(chunk) }
        try {
            rec.start()
        } catch (e: Exception) {
            _status.value = KbStatus.Message(e.message ?: "Couldn't start the microphone")
            return
        }
        recorder = rec
        probe = app.metrics.startProbe(app.appScope)
        _liveText.value = ""
        _status.value = KbStatus.Recording
        val myToken = ++token

        scope.launch {
            val engine = app.engine.get()
            if (myToken != token) return@launch // aborted while loading
            if (engine == null) {
                abort((app.engine.state.value as? EngineState.Failed)?.message ?: "Couldn't load the speech model")
                return@launch
            }
            val s = DictationSession(engine, app.settings.paragraphPauseSec.value, app.appScope, app.engine.dispatcher)
            s.start()
            session = s
            synchronized(lock) {
                earlyAudio.forEach { s.feed(it) }
                earlyAudio.clear()
                target = s
            }
            liveJob = launch { s.liveText.collect { _liveText.value = it } }
        }
    }

    /** Mic thread. */
    private fun onAudio(chunk: FloatArray) {
        synchronized(lock) {
            val t = target
            if (t != null) t.feed(chunk) else earlyAudio.add(chunk)
        }
        var sum = 0.0
        for (s in chunk) sum += s * s
        val db = 20 * log10(sqrt(sum / chunk.size).coerceAtLeast(1e-6))
        val level = ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
        val cur = _levels.value
        _levels.value = cur.subList(1, cur.size) + level
    }

    private fun stop(insert: Boolean) {
        val rec = recorder ?: return
        val stopAt = SystemClock.elapsedRealtime()
        _status.value = KbStatus.Finishing
        scope.launch {
            withContext(Dispatchers.Default) { rec.stop() }
            recorder = null
            // If the model was still loading when you tapped stop, wait for it (audio is buffered).
            val myToken = token
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (session == null && myToken == token && SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.delay(50)
            }
            if (myToken != token) return@launch
            val s = session
            if (s == null) {
                abort("Speech model took too long to load")
                return@launch
            }
            val raw = s.finish()
            liveJob?.cancel()
            session = null
            synchronized(lock) { target = null }
            val (pss, uah) = probe?.stop() ?: (0 to null)
            probe = null
            _levels.value = List(WAVE_BARS) { 0f }

            if (raw.isBlank()) {
                _status.value = KbStatus.Message("Didn't catch anything. Try again.")
                return@launch
            }
            val inserted = insert && commit(raw)
            if (!inserted) _pending.value = raw
            val ms = SystemClock.elapsedRealtime() - stopAt
            app.metrics.add(
                SessionMetrics(
                    timestamp = System.currentTimeMillis(), source = "mic",
                    modelId = s.modelId,
                    audioSec = s.audioSeconds, phrases = s.phraseCount, stopToTextMs = ms,
                    asrBusyMs = s.asrBusyMs, peakPssMb = pss, chargeUsedUah = uah,
                ),
            )
            _liveText.value = ""
            _status.value = KbStatus.Idle
        }
    }

    private fun abort(message: String) {
        token++
        recorder?.stop()
        recorder = null
        session?.cancel()
        session = null
        liveJob?.cancel()
        synchronized(lock) { target = null; earlyAudio.clear() }
        probe?.stop()
        probe = null
        _levels.value = List(WAVE_BARS) { 0f }
        _status.value = KbStatus.Message(message)
    }

    fun insertPending() {
        val p = _pending.value ?: return
        if (commit(p)) _pending.value = null
    }

    fun dismissPending() {
        _pending.value = null
    }

    private fun commit(raw: String): Boolean {
        val ic = service.currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(64, 0)
        val after = ic.getTextAfterCursor(16, 0)
        val text = InsertionFormatter.format(raw, before, after)
        ic.beginBatchEdit()
        val ok = ic.commitText(text, 1)
        ic.endBatchEdit()
        return ok
    }

    fun backspace() {
        val ic = service.currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    fun enter() {
        val ic = service.currentInputConnection ?: return
        val ei: EditorInfo? = service.currentInputEditorInfo
        val options = ei?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = (ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!noEnterAction && !multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun switchKeyboard() {
        if (isBusy) return
        if (!service.switchToPreviousInputMethod()) showPicker()
    }

    fun showPicker() {
        service.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    fun openApp() {
        service.startActivity(
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun onDestroy() {
        if (recorder != null || session != null) abort("")
    }

    companion object {
        const val WAVE_BARS = 48
    }
}
