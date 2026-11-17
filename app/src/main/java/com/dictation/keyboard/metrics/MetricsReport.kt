package com.dictation.keyboard.metrics

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text summary you can copy and paste back to me. */
object MetricsReport {

    fun summary(list: List<SessionMetrics>): String {
        if (list.isEmpty()) return "No sessions yet."
        val stops = list.map { it.stopToTextMs }.sorted()
        val median = stops[stops.size / 2]
        val worst = stops.last()
        val peak = list.maxOf { it.peakPssMb }
        val rtf = list.filter { it.audioSec > 1 }.map { it.realTimeFactor }
        val battery = list.filter { it.chargeUsedUah != null && it.source == "mic" }
        val batteryLine = if (battery.isEmpty()) {
            "Battery: not enough data yet (needs mic sessions while unplugged)."
        } else {
            val uah = battery.sumOf { it.chargeUsedUah!! }
            val sec = battery.sumOf { it.audioSec }
            val per10 = if (sec > 0) uah / 1000.0 / sec * 600 else 0.0
            "Battery (whole phone, while dictating): %.0f mAh per 10 min, from %.1f min of dictation".format(per10, sec / 60)
        }
        return buildString {
            appendLine("Sessions: ${list.size}")
            appendLine("Stop → text: median $median ms, worst $worst ms")
            appendLine("Peak memory (PSS): $peak MB")
            if (rtf.isNotEmpty()) appendLine("Real-time factor: %.3f (lower is faster)".format(rtf.average()))
            append(batteryLine)
        }
    }

    fun full(list: List<SessionMetrics>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        return buildString {
            appendLine("Dictation Keyboard metrics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, SoC ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}")
            appendLine()
            appendLine(summary(list))
            appendLine()
            appendLine("time, source, model, audio s, blocks, stop→text ms, asr ms, RTF, peak MB, µAh, cleanup ms, cleanup")
            list.forEach {
                appendLine(
                    "%s, %s, %s, %.1f, %d, %d, %d, %.3f, %d, %s, %d, %s".format(
                        fmt.format(Date(it.timestamp)), it.source, it.modelId, it.audioSec, it.phrases,
                        it.stopToTextMs, it.asrBusyMs, it.realTimeFactor, it.peakPssMb, it.chargeUsedUah?.toString() ?: "-",
                        it.cleanupMs, it.cleanup,
                    ),
                )
            }
        }
    }
}
