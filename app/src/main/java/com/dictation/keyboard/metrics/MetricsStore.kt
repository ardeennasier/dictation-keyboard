package com.dictation.keyboard.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One dictation's measurements, for the acceptance-test report. */
data class SessionMetrics(
    val timestamp: Long,
    val source: String, // "mic" or "file"
    val modelId: String,
    val audioSec: Double,
    val phrases: Int,
    /** Tap stop (or end of file) → text handed to the app. */
    val stopToTextMs: Long,
    /** Total time the recogniser spent working, for the real-time factor. */
    val asrBusyMs: Long,
    val peakPssMb: Int,
    /** Whole-phone battery charge used during the session, if the phone reports it and wasn't charging. */
    val chargeUsedUah: Long?,
    /** Total clean-up time across blocks, and how blocks were cleaned (e.g. "ai×3", "light×1"). */
    val cleanupMs: Long = 0,
    val cleanup: String = "",
) {
    val realTimeFactor: Double get() = if (audioSec > 0) asrBusyMs / 1000.0 / audioSec else 0.0

    fun toJson() = JSONObject().apply {
        put("t", timestamp); put("src", source); put("model", modelId); put("audio", audioSec)
        put("phrases", phrases); put("stop", stopToTextMs); put("asr", asrBusyMs); put("pss", peakPssMb)
        if (chargeUsedUah != null) put("uah", chargeUsedUah)
        put("cms", cleanupMs); put("cm", cleanup)
    }

    companion object {
        fun fromJson(o: JSONObject) = SessionMetrics(
            o.getLong("t"), o.getString("src"), o.getString("model"), o.getDouble("audio"),
            o.getInt("phrases"), o.getLong("stop"), o.getLong("asr"), o.getInt("pss"),
            if (o.has("uah")) o.getLong("uah") else null,
            o.optLong("cms", 0), o.optString("cm", ""),
        )
    }
}

class MetricsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("metrics", Context.MODE_PRIVATE)

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<SessionMetrics>> = _sessions

    @Synchronized
    fun add(m: SessionMetrics) {
        val list = (listOf(m) + _sessions.value).take(MAX)
        prefs.edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
        _sessions.value = list
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
        _sessions.value = emptyList()
    }

    private fun load(): List<SessionMetrics> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { SessionMetrics.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Starts sampling memory and battery; call [Probe.stop] at the end of the session. */
    fun startProbe(scope: CoroutineScope): Probe = Probe(appContext, scope)

    class Probe internal constructor(private val context: Context, scope: CoroutineScope) {
        @Volatile private var peakPssKb = Debug.getPss()
        private val startCharge = chargeCounter()
        private val startedCharging = isCharging()
        private val job: Job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val pss = Debug.getPss()
                if (pss > peakPssKb) peakPssKb = pss
                delay(500)
            }
        }

        fun stop(): Pair<Int, Long?> {
            job.cancel()
            val pss = Debug.getPss()
            if (pss > peakPssKb) peakPssKb = pss
            val end = chargeCounter()
            val used = if (startCharge != null && end != null && !startedCharging && !isCharging()) {
                (startCharge - end).coerceAtLeast(0)
            } else null
            return (peakPssKb / 1024).toInt() to used
        }

        private fun chargeCounter(): Long? {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return null
            val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            return if (v == Int.MIN_VALUE || v <= 0) null else v.toLong()
        }

        private fun isCharging(): Boolean {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private companion object {
        const val KEY = "sessions"
        const val MAX = 50
    }
}
