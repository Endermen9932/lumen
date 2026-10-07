package app.lumen.photos.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import app.lumen.photos.face.FaceModelCatalog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stores model weights in the app's private storage and downloads them on request.
 * Downloads resume where they stopped, so a cancelled multi-GB download is not lost.
 */
class ModelManager(private val context: Context) {

    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val _installed = MutableStateFlow(scanInstalled())

    /** IDs of all models whose files are completely present. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun dir(model: DownloadableModel) = File(root, model.id)
    fun file(model: DownloadableModel, file: ModelFile) = File(dir(model), file.localName)
    fun tokenizerCache(model: AiModel) = File(dir(model), "tokenizer.bin")

    fun isInstalled(model: DownloadableModel): Boolean = model.files.all { f ->
        val local = file(model, f)
        local.exists() && (f.sizeBytes <= 0 || local.length() == f.sizeBytes)
    }

    fun downloadedBytes(model: DownloadableModel): Long = model.files.sumOf { f ->
        val done = file(model, f)
        val part = File(done.path + ".part")
        when {
            done.exists() -> done.length()
            part.exists() -> part.length()
            else -> 0L
        }
    }

    fun diskUsage(model: DownloadableModel): Long = dir(model).listFiles()?.sumOf { it.length() } ?: 0L

    private fun scanInstalled(): Set<String> = (ModelCatalog.models + FaceModelCatalog.models).filter { isInstalled(it) }.map { it.id }.toSet()

    fun refresh() {
        _installed.value = scanInstalled()
    }

    /** Called after a model was deleted by the user (the developer backup mirrors that). */
    var onDeleted: ((DownloadableModel) -> Unit)? = null

    fun delete(model: DownloadableModel) {
        dir(model).deleteRecursively()
        refresh()
        onDeleted?.invoke(model)
    }

    /**
     * Downloads every missing file of [model]. [onProgress] receives (downloadedBytes, totalBytes).
     * Cooperative with coroutine cancellation.
     */
    suspend fun download(model: DownloadableModel, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dir(model).mkdirs()
        val total = model.totalBytes
        var base = 0L
        for (f in model.files) {
            val target = file(model, f)
            if (target.exists() && (f.sizeBytes <= 0 || target.length() == f.sizeBytes)) {
                base += target.length()
                onProgress(base, total)
                continue
            }
            val part = File(target.path + ".part")
            var attempt = 0
            while (true) {
                try {
                    downloadFile(model.url(f), part) { done -> onProgress(base + done, total) }
                    break
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (++attempt >= 5) throw e
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            if (f.sizeBytes > 0 && part.length() != f.sizeBytes) {
                part.delete()
                throw IOException("Unvollständige Datei ${f.path}")
            }
            check(part.renameTo(target)) { "Konnte ${target.name} nicht speichern" }
            base += target.length()
        }
        refresh()
    }

    private suspend fun downloadFile(url: String, part: File, onProgress: suspend (Long) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        var current = URL(url)
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LumenPhotos/1.0 (Android)")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect ohne Ziel")
                connection.disconnect()
                current = URL(current, location)
                if (++redirects > 10) throw IOException("Zu viele Weiterleitungen")
                continue
            }
            if (code == 416) {
                // Already complete.
                connection.disconnect()
                return
            }
            if (code != 200 && code != 206) {
                connection.disconnect()
                throw IOException("HTTP $code für $url")
            }
            if (code == 200) existing = 0L
            break
        }
        try {
            connection.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(done)
                        }
                    }
                    onProgress(done)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Imports model files manually picked by the user (for fully offline setups). The files are
     * matched by name against the model's file list.
     */
    suspend fun import(model: DownloadableModel, uris: List<Uri>, nameOf: (Uri) -> String?): Int = withContext(Dispatchers.IO) {
        dir(model).mkdirs()
        var imported = 0
        for (uri in uris) {
            val name = nameOf(uri) ?: continue
            val match = model.files.firstOrNull { it.localName == name || it.path.substringAfterLast('/') == name } ?: continue
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file(model, match)).use { input.copyTo(it, 1 shl 16) }
                imported++
            }
        }
        refresh()
        imported
    }
}
