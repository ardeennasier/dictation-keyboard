package com.dictation.keyboard.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * A speech model installed on the phone. Each model folder holds a manifest.json written by the
 * project's "Package models" GitHub workflow, the model files, and the Silero VAD model.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    /** "nemo_transducer" (Parakeet) or "moonshine". */
    val type: String,
    val dir: File,
    val files: Map<String, String>,
    val vadFile: String,
    val sizeBytes: Long,
) {
    fun path(key: String): String =
        File(dir, files[key] ?: throw IllegalStateException("Model $id is missing '$key'")).absolutePath

    val vadPath: String get() = File(dir, vadFile).absolutePath
}

class ModelStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "models").apply { mkdirs() }

    fun installed(): List<ModelInfo> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { runCatching { readManifest(it) }.getOrNull() }
            .sortedBy { it.name }

    fun find(id: String?): ModelInfo? = installed().let { list -> list.find { it.id == id } ?: list.firstOrNull() }

    fun delete(id: String) {
        File(root, id).takeIf { it.isDirectory }?.deleteRecursively()
    }

    /**
     * Unzips a model package picked with the system file picker. Runs on the caller's thread:
     * call it from Dispatchers.IO. [onProgress] gets a 0..1 fraction (or -1 if size unknown).
     */
    fun importZip(uri: Uri, onProgress: (Float) -> Unit): ModelInfo {
        val total = querySize(uri)
        val tmp = File(root, ".import-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val resolver = appContext.contentResolver
            val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
            val counting = CountingInputStream(input)
            ZipInputStream(counting.buffered(1 shl 16)).use { zip ->
                val buf = ByteArray(1 shl 16)
                var lastReported = 0f
                val tmpCanon = tmp.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Flatten folders and refuse path tricks ("zip slip").
                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name.startsWith(".")) continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(tmpCanon)) throw IOException("Bad entry: ${entry.name}")
                    out.outputStream().buffered(1 shl 16).use { os ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            if (total > 0) {
                                val f = (counting.count.toFloat() / total).coerceIn(0f, 1f)
                                if (f - lastReported >= 0.01f) {
                                    lastReported = f
                                    onProgress(f)
                                }
                            }
                        }
                    }
                }
            }
            if (!File(tmp, "manifest.json").exists()) {
                throw IOException("This isn't a model package (no manifest.json). Download a models-*.zip from the project's Releases page.")
            }
            val parsed = readManifest(tmp) // validates every file is present
            val dest = File(root, parsed.id)
            if (dest.exists()) dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't move model into place")
            return readManifest(dest)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun readManifest(dir: File): ModelInfo {
        val json = JSONObject(File(dir, "manifest.json").readText())
        val filesJson = json.getJSONObject("files")
        val files = filesJson.keys().asSequence().associateWith { filesJson.getString(it) }
        val vad = json.getString("vad")
        (files.values + vad).forEach {
            if (!File(dir, it).isFile) throw IOException("Model package is incomplete: missing $it")
        }
        val type = json.getString("type")
        require(type == "nemo_transducer" || type == "moonshine") { "Unsupported model type: $type" }
        return ModelInfo(
            id = json.getString("id"),
            name = json.getString("name"),
            type = type,
            dir = dir,
            files = files,
            vadFile = vad,
            sizeBytes = dir.listFiles().orEmpty().sumOf { it.length() },
        )
    }

    private fun querySize(uri: Uri): Long =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        @Volatile var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
