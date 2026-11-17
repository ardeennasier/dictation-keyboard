package com.dictation.keyboard

import android.app.Application
import android.content.Context
import com.dictation.keyboard.asr.EngineHolder
import com.dictation.keyboard.metrics.MetricsStore
import com.dictation.keyboard.model.ModelStore
import com.dictation.keyboard.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DictationApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: AppSettings private set
    lateinit var models: ModelStore private set
    lateinit var engine: EngineHolder private set
    lateinit var metrics: MetricsStore private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        models = ModelStore(this)
        engine = EngineHolder(models, settings, appScope)
        metrics = MetricsStore(this)
    }
}

val Context.app: DictationApp get() = applicationContext as DictationApp
