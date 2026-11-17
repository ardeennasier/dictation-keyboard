package com.dictation.keyboard.ime

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dictation.keyboard.asr.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KeyboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun KeyboardScreen(c: KeyboardController) {
    KeyboardTheme {
        val status by c.status.collectAsState()
        val live by c.liveText.collectAsState()
        val levels by c.levels.collectAsState()
        val pending by c.pending.collectAsState()
        val engine by c.engineState.collectAsState()

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(272.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    TopArea(status, live, pending, engine, c)
                }
                Waveform(
                    levels = levels,
                    active = status == KbStatus.Recording,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(104.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyButton(Icons.Filled.Keyboard, "Switch keyboard", onClick = c::switchKeyboard, onLongClick = c::showPicker)
                    Spacer(Modifier.weight(1f))
                    MicButton(status, engine, onClick = c::onMicTap)
                    Spacer(Modifier.weight(1f))
                    RepeatingKey(Icons.AutoMirrored.Filled.Backspace, "Delete", c::backspace)
                    Spacer(Modifier.width(8.dp))
                    KeyButton(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", onClick = c::enter)
                }
            }
        }
    }
}

@Composable
private fun TopArea(status: KbStatus, live: String, pending: String?, engine: EngineState, c: KeyboardController) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        KbStatus.Recording, KbStatus.Finishing -> {
            val scroll = rememberScrollState()
            LaunchedEffect(live) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                val loading = engine is EngineState.Loading
                Text(
                    text = when {
                        live.isNotEmpty() -> live
                        loading -> "Loading speech model… keep talking, I'm recording."
                        status == KbStatus.Recording -> "Listening…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (live.isEmpty()) muted else MaterialTheme.colorScheme.onSurface,
                )
                if (status == KbStatus.Finishing) {
                    Text("Finishing…", style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
        }
        is KbStatus.Message -> Text(
            status.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxSize().clickable { c.onMessageTap() }.padding(4.dp),
        )
        KbStatus.Idle -> {
            if (pending != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = c::insertPending, label = { Text("Insert last dictation") })
                    IconButton(onClick = c::dismissPending) { Icon(Icons.Filled.Close, "Discard last dictation") }
                }
            } else {
                Text(
                    text = when (engine) {
                        is EngineState.Loading -> "Loading ${engine.modelName}…"
                        is EngineState.Failed -> "Model error: ${engine.message}"
                        is EngineState.NoModel -> "No speech model installed. Open the app to add one."
                        else -> "Tap the mic and speak. Tap again to insert."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun MicButton(status: KbStatus, engine: EngineState, onClick: () -> Unit) {
    val recording = status == KbStatus.Recording
    val finishing = status == KbStatus.Finishing
    val scale by animateFloatAsState(if (recording) 1.08f else 1f, label = "mic")
    val bg = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val fg = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size((92 * scale).dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = !finishing, onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop and insert" else "Start dictation" },
    ) {
        if (finishing) {
            CircularProgressIndicator(color = fg, modifier = Modifier.size(36.dp))
        } else {
            Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = fg, modifier = Modifier.size(44.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(icon: ImageVector, label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Deletes once on press, then repeats while held. */
@Composable
private fun RepeatingKey(icon: ImageVector, label: String, action: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        action()
                        delay(400)
                        while (true) {
                            action()
                            delay(50)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            }
            .semantics { contentDescription = label },
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Waveform(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val minH = 3.dp.toPx()
        levels.forEachIndexed { i, lv ->
            val h = (minH + (size.height - minH) * (if (active) lv else 0f))
            val x = i * (barW + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}
