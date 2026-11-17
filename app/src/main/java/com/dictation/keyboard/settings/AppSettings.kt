package com.dictation.keyboard.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _selectedModelId = MutableStateFlow(prefs.getString(KEY_MODEL, null))
    val selectedModelId: StateFlow<String?> = _selectedModelId

    private val _paragraphPauseSec = MutableStateFlow(prefs.getFloat(KEY_PARAGRAPH, 2.0f))
    val paragraphPauseSec: StateFlow<Float> = _paragraphPauseSec

    fun setSelectedModel(id: String?) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        _selectedModelId.value = id
    }

    fun setParagraphPause(sec: Float) {
        prefs.edit().putFloat(KEY_PARAGRAPH, sec).apply()
        _paragraphPauseSec.value = sec
    }

    private companion object {
        const val KEY_MODEL = "selected_model"
        const val KEY_PARAGRAPH = "paragraph_pause_sec"
    }
}
