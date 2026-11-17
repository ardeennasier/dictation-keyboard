package com.dictation.keyboard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dictation.keyboard.BuildConfig
import com.dictation.keyboard.app
import com.dictation.keyboard.asr.EngineState
import com.dictation.keyboard.asr.FileTest
import com.dictation.keyboard.cleanup.AiStatus
import com.dictation.keyboard.cleanup.CleanupResult
import com.dictation.keyboard.cleanup.CleanupStrength
import com.google.mlkit.genai.common.DownloadStatus
import com.dictation.keyboard.ime.KeyboardTheme
import com.dictation.keyboard.metrics.MetricsReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeyboardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()

    // Re-check system state once a second (permission, keyboard enabled/selected) so the
    // ticks update when you come back from Settings or the keyboard picker.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val micGranted = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    val enabled = remember(tick) { imm.enabledInputMethodList.any { it.packageName == ctx.packageName } }
    val selected = remember(tick) {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(ctx.packageName + "/") == true
    }
    var modelsVersion by remember { mutableIntStateOf(0) }
    val models = remember(modelsVersion) { app.models.installed() }
    val selectedModelId by app.settings.selectedModelId.collectAsState()
    val engineState by app.engine.state.collectAsState()

    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        importProgress = 0f
        scope.launch {
            try {
                app.engine.unload()
                val info = withContext(Dispatchers.IO) {
                    app.models.importZip(uri) { p -> importProgress = p }
                }
                app.settings.setSelectedModel(info.id)
                modelsVersion++
                app.engine.preload()
            } catch (e: Exception) {
                importError = e.message ?: "Import failed"
            } finally {
                importProgress = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Dictation Keyboard", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Speak into any text field. Everything runs on this phone; the app has no internet permission.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("Set up") {
            Step(1, "Allow the microphone", micGranted) {
                Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
            }
            Step(2, "Add a speech model", models.isNotEmpty()) {
                Text(
                    "Download a models-*.zip from the project's Releases page in your browser, then import it here. One-time; about 650 MB for Parakeet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (BuildConfig.MODELS_URL.isNotBlank()) {
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.MODELS_URL)))
                        }) { Text("Open download page") }
                    }
                    Button(
                        enabled = importProgress == null,
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    ) { Text("Import file") }
                }
                importProgress?.let {
                    Text("Unpacking…", style = MaterialTheme.typography.bodySmall)
                    if (it > 0f) LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Step(3, "Turn on the keyboard", enabled) {
                Text(
                    "Android will warn that a keyboard can read what you type. That's shown for every third-party keyboard.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("Open keyboard settings") }
            }
            Step(4, "Switch to it", selected) {
                Button(onClick = { imm.showInputMethodPicker() }, enabled = enabled) { Text("Choose keyboard") }
            }
        }

        Section("Speech model") {
            if (models.isEmpty()) Text("None installed yet.", style = MaterialTheme.typography.bodyMedium)
            val activeId = app.models.find(selectedModelId)?.id
            models.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m.id == activeId, onClick = {
                        app.settings.setSelectedModel(m.id)
                        app.engine.preload()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text("%.0f MB".format(m.sizeBytes / 1e6), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            app.engine.unload()
                            withContext(Dispatchers.IO) { app.models.delete(m.id) }
                            modelsVersion++
                        }
                    }) { Icon(Icons.Filled.Delete, "Delete ${m.name}") }
                }
            }
            Text(
                when (val s = engineState) {
                    EngineState.NotLoaded -> "Not loaded (loads when you open the keyboard)."
                    EngineState.NoModel -> "No model."
                    is EngineState.Loading -> "Loading ${s.modelName}…"
                    is EngineState.Ready -> "Loaded ${s.modelName} in ${s.loadMs} ms."
                    is EngineState.Failed -> "Failed to load: ${s.message}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Settings") {
            val pause by app.settings.paragraphPauseSec.collectAsState()
            var sliderValue by remember { mutableFloatStateOf(pause) }
            Text("New paragraph after a pause of %.1f s".format(sliderValue), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it * 2).toInt() / 2f },
                onValueChangeFinished = { app.settings.setParagraphPause(sliderValue) },
                valueRange = 1f..5f,
                steps = 7,
            )
        }

        CleanupSection()

        Section("Try it here") {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("Tap here, then use the mic") },
            )
        }

        FileTestSection()
        MetricsSection()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CleanupSection() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val strength by app.settings.cleanupStrength.collectAsState()
    var aiStatus by remember { mutableStateOf<AiStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var downloadMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { aiStatus = app.cleaner.aiStatus(refresh = true) }

    Section("Clean-up") {
        Text(
            "Removes fillers, repeats, false starts and self-corrections. It only ever deletes words, never adds or rewords them.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(
            CleanupStrength.OFF to "Off: exactly what you said",
            CleanupStrength.LIGHT to "Light: um/uh and stutters (\"the the\")",
            CleanupStrength.STRONG to "Strong: also false starts and self-corrections (uses on-device AI)",
        ).forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = strength == value, onClick = { app.settings.setCleanupStrength(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "On-device AI (Gemini Nano): " + when (val st = aiStatus) {
                null -> "checking…"
                AiStatus.Available -> "available ✓"
                AiStatus.Downloadable -> "supported, not downloaded yet"
                AiStatus.Downloading -> "downloading…"
                AiStatus.Unavailable -> "not available on this phone. Strong works like Light for now."
                is AiStatus.Error -> "error: ${st.message}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (aiStatus == AiStatus.Downloadable) {
            Button(onClick = {
                downloadMsg = "Starting…"
                scope.launch {
                    try {
                        app.cleaner.downloadAi().collect { d ->
                            downloadMsg = when (d) {
                                is DownloadStatus.DownloadStarted -> "Downloading (by Android's AICore)…"
                                is DownloadStatus.DownloadProgress -> "Downloaded %.0f MB…".format(d.totalBytesDownloaded / 1e6)
                                is DownloadStatus.DownloadCompleted -> "Done."
                                is DownloadStatus.DownloadFailed -> "Failed: ${d.e.message}"
                                else -> downloadMsg
                            }
                        }
                    } catch (e: Exception) {
                        downloadMsg = "Failed: ${e.message}"
                    }
                    refresh++
                }
            }) { Text("Download Gemini Nano") }
            Text(
                "Android's AICore service downloads it, not this app (which has no internet access). Needs a connection once.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        downloadMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { refresh++ }) { Text("Re-check") }

        var input by remember { mutableStateOf("So um I think we should, we should meet on Tuesday, no, Wednesday. Let's see how the how this works.") }
        var output by remember { mutableStateOf<CleanupResult?>(null) }
        var busy by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Try clean-up on any text") },
        )
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                output = app.cleaner.clean(input, strength)
                busy = false
            }
        }) { Text(if (busy) "Cleaning…" else "Clean up") }
        output?.let { r ->
            SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyMedium) }
            Text("${r.method}, ${r.ms} ms" + (r.note?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileTestSection() {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var realTime by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FileTest.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        running = true
        error = null
        result = null
        scope.launch {
            try {
                result = FileTest.run(app, uri, realTime) { msg -> progress = msg }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }

    Section("Test with a recording") {
        Text(
            "Runs an audio file (from any voice recorder app) through the same pipeline as the keyboard. Use it for repeatable tests, e.g. the 2-minute ramble.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = realTime, onCheckedChange = { realTime = it }, enabled = !running)
            Spacer(Modifier.width(8.dp))
            Text(if (realTime) "Real-time (realistic stop→text)" else "As fast as possible", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !running) { Text("Pick audio file") }
        if (running) {
            Text(progress, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { r ->
            val m = r.metrics
            Text(
                "%.1f s of audio, %d blocks. Stop→text %d ms. Real-time factor %.3f. Peak memory %d MB. Clean-up %d ms (%s).".format(
                    m.audioSec, m.phrases, m.stopToTextMs, m.realTimeFactor, m.peakPssMb, m.cleanupMs, m.cleanup,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Cleaned", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(r.transcript.cleaned, style = MaterialTheme.typography.bodyMedium) }
            Text("Exact transcript", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(r.transcript.raw, style = MaterialTheme.typography.bodyMedium) }
            r.transcript.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copy(ctx, "Transcript", "CLEANED:\n${r.transcript.cleaned}\n\nRAW:\n${r.transcript.raw}") }) { Text("Copy both") }
            }
        }
    }
}

@Composable
private fun MetricsSection() {
    val ctx = LocalContext.current
    val app = ctx.app
    val sessions by app.metrics.sessions.collectAsState()
    Section("Measurements") {
        Text(MetricsReport.summary(sessions), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copy(ctx, "Metrics", MetricsReport.full(sessions)) }, enabled = sessions.isNotEmpty()) { Text("Copy report") }
            OutlinedButton(onClick = { app.metrics.clear() }, enabled = sessions.isNotEmpty()) { Text("Clear") }
        }
    }
}

private fun copy(ctx: android.content.Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (done) "Done" else "Not done",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("$n. $title", style = MaterialTheme.typography.bodyLarge)
        }
        if (!done) Column(Modifier.padding(start = 30.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
