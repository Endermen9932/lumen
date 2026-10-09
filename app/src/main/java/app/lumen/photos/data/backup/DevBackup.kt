package app.lumen.photos.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.room.InvalidationTracker
import app.lumen.photos.AppContainer
import app.lumen.photos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Contents of `backup.json` in the backup folder. */
@Serializable
data class DevBackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val installId: String,
    val models: List<String>,
    val modelBytes: Long,
    val databaseBytes: Long,
)

data class DevBackupState(
    val running: Boolean = false,
    /** What is happening right now, e.g. "Modelle sichern …". */
    val step: String? = null,
    val progress: Float? = null,
    val info: DevBackupInfo? = null,
    /** The backup in the folder was made by another installation (e.g. before reinstalling). */
    val foreign: Boolean = false,
    val error: String? = null,
)

/**
 * Developer mode: keeps a complete copy of everything the app produces – AI and face models,
 * search index, faces & names, optimiser history and all settings – in `Documents/Photos`.
 * That folder survives uninstalling, so after installing a new version everything is restored
 * with one tap instead of downloading, indexing and configuring again.
 *
 * Needs "Zugriff auf alle Dateien": without it an app cannot read files a previous installation
 * created in shared storage.
 */
class DevBackup(private val context: Context, private val c: AppContainer) {

    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(DevBackupState())
    val state: StateFlow<DevBackupState> = _state.asStateFlow()

    /** The backup folder chosen by the user (default `Documents/Photos`). */
    val root: File get() = c.settings.current.devBackupPath?.let(::File) ?: defaultRoot

    private val modelsDir get() = File(context.filesDir, "models")
    private val settingsFile get() = File(context.filesDir, "datastore/$SETTINGS_FILE")

    /** Stable id of this installation; a restore adopts the id of the backup. */
    private val installId: String
        get() = File(context.filesDir, INSTALL_ID_FILE).let { f ->
            f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { f.writeText(it) }
        }

    fun start(justRestored: Boolean) {
        refreshInfo()
        if (justRestored) {
            c.scope.launch {
                val source = File(context.filesDir, RESTORED_FROM_FILE)
                source.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
                    c.settings.update { it.copy(devBackupPath = path.takeIf { p -> File(p) != defaultRoot }) }
                    refreshInfo()
                }
                source.delete()
                // The SAF permission of the photo backup target does not survive a reinstall.
                c.settings.updateBackup { it.copy(treeUri = null, folderName = null, autoBackup = false) }
            }
        }
        c.models.onDeleted = { model ->
            if (enabled()) runCatching { File(root, "models/${model.id}").deleteRecursively() }
            requestBackup()
        }
        // Everything that changes app files requests a backup.
        c.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) = requestBackup()
        })
        c.settings.settings.drop(1).onEach { requestBackup() }.launchIn(c.scope)
        c.models.installed.drop(1).onEach { requestBackup() }.launchIn(c.scope)
        c.settings.settings.map { it.developerMode }.distinctUntilChanged().onEach { on ->
            if (on) { refreshInfo(); requestBackup() }
        }.launchIn(c.scope)

        c.scope.launch {
            while (true) {
                requests.receive()
                // Let bursts settle (indexing writes every few seconds), but back up at least
                // every 10 minutes while something keeps changing.
                val first = System.currentTimeMillis()
                while (System.currentTimeMillis() - first < MAX_DELAY_MS) {
                    withTimeoutOrNull(SETTLE_MS) { requests.receive() } ?: break
                }
                if (enabled() && !_state.value.foreign) runCatching { backupNow() }
            }
        }
    }

    fun requestBackup() {
        if (enabled()) requests.trySend(Unit)
    }

    private fun enabled() = c.settings.current.developerMode && hasAccess()

    fun refreshInfo() {
        val info = if (hasAccess()) readInfo() else null
        _state.update { it.copy(info = info, foreign = info != null && info.installId != installId) }
    }

    private fun readInfo(dir: File = root): DevBackupInfo? = runCatching {
        json.decodeFromString(DevBackupInfo.serializer(), File(dir, MANIFEST).readText())
    }.getOrNull()

    /** Backup info of another folder (for "Wiederherstellen aus …"). */
    fun infoOf(dir: File): DevBackupInfo? = if (hasAccess()) readInfo(dir) else null

    /** Moves future backups to [dir] (the old folder is left as it is) and backs up right away. */
    suspend fun setFolder(dir: File) {
        c.settings.update { it.copy(devBackupPath = dir.absolutePath.takeIf { _ -> dir != defaultRoot }) }
        refreshInfo()
        if (enabled() && !_state.value.foreign) backupNow()
    }

    // ------------------------------------------------------------------ backup

    /** Copies everything to [root]. Models are copied incrementally, the rest every time. */
    suspend fun backupNow(): Result<DevBackupInfo> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(hasAccess()) { "Kein Zugriff auf alle Dateien" }
                _state.update { it.copy(running = true, step = "Modelle sichern …", progress = null, error = null) }
                val target = root.apply { mkdirs() }
                check(target.isDirectory) { "Ordner ${target.path} konnte nicht angelegt werden" }

                // Models (only complete files – running downloads end with .part).
                val models = modelsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
                val files = models.flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }.orEmpty() }
                val modelBytes = files.sumOf { it.length() }
                var copied = 0L
                for (src in files) {
                    val dst = File(target, "models/${src.parentFile!!.name}/${src.name}")
                    if (!dst.exists() || dst.length() != src.length()) copyAtomically(src, dst) { n ->
                        _state.update { it.copy(progress = ((copied + n).toFloat() / modelBytes.coerceAtLeast(1)).coerceIn(0f, 1f)) }
                    }
                    copied += src.length()
                }

                // Database: VACUUM INTO gives a consistent snapshot while indexing keeps writing.
                _state.update { it.copy(step = "Index & Gesichter sichern …", progress = null) }
                val snapshot = File(context.cacheDir, "backup-$DB_NAME").apply { delete() }
                c.db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'")
                val databaseBytes = snapshot.length()
                copyAtomically(snapshot, File(target, DB_NAME))
                snapshot.delete()

                _state.update { it.copy(step = "Einstellungen sichern …") }
                if (settingsFile.exists()) copyAtomically(settingsFile, File(target, SETTINGS_FILE))

                val info = DevBackupInfo(
                    createdAt = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    installId = installId,
                    models = models.map { it.name }.sorted(),
                    modelBytes = modelBytes,
                    databaseBytes = databaseBytes,
                )
                File(target, MANIFEST).writeText(json.encodeToString(DevBackupInfo.serializer(), info))
                info
            }.also { result ->
                _state.update {
                    it.copy(
                        running = false, step = null, progress = null,
                        info = result.getOrNull() ?: it.info,
                        foreign = if (result.isSuccess) false else it.foreign,
                        error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Copies the models back right away and stages database and settings; they are moved into
     * place by [applyPendingRestore] on the next start. Afterwards call [restartApp].
     */
    suspend fun restore(from: File = root): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(hasAccess()) { "Kein Zugriff auf alle Dateien" }
                val root = from
                val info = readInfo(root) ?: error("Kein Backup in ${folderLabel(root)} gefunden")
                // Nothing may write into the database that is about to be replaced.
                c.ai.cancelIndexing()
                c.faces.cancel()
                _state.update { it.copy(running = true, step = "Modelle wiederherstellen …", progress = 0f, error = null) }

                val files = File(root, "models").listFiles()?.filter { it.isDirectory }.orEmpty()
                    .flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty() }
                val total = files.sumOf { it.length() }.coerceAtLeast(1)
                var copied = 0L
                for (src in files) {
                    val dst = File(modelsDir, "${src.parentFile!!.name}/${src.name}")
                    if (!dst.exists() || dst.length() != src.length()) copyAtomically(src, dst) { n ->
                        _state.update { it.copy(progress = ((copied + n).toFloat() / total).coerceIn(0f, 1f)) }
                    }
                    copied += src.length()
                }
                c.models.refresh()

                _state.update { it.copy(step = "Index, Gesichter & Einstellungen vorbereiten …", progress = null) }
                val pending = pendingDir(context).apply { deleteRecursively(); mkdirs() }
                File(root, DB_NAME).takeIf { it.exists() }?.copyTo(File(pending, DB_NAME), overwrite = true)
                File(root, SETTINGS_FILE).takeIf { it.exists() }?.copyTo(File(pending, SETTINGS_FILE), overwrite = true)
                File(pending, READY_MARKER).writeText(info.installId)
                File(pending, SOURCE_FILE).writeText(root.absolutePath)
                _state.update { it.copy(step = "Neustart …") }
            }.onFailure { e ->
                pendingDir(context).deleteRecursively()
                _state.update { it.copy(running = false, step = null, progress = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun copyAtomically(src: File, dst: File, onProgress: (Long) -> Unit = {}) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        src.inputStream().use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - lastReport > 8L * 1024 * 1024) {
                        lastReport = done
                        onProgress(done)
                    }
                }
                out.fd.sync()
            }
        }
        dst.delete()
        check(tmp.renameTo(dst)) { "Konnte ${dst.name} nicht speichern" }
    }

    companion object {
        /** `Documents/Photos` on the internal shared storage. */
        val defaultRoot: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Photos")

        /** Short, readable form of a backup folder: "Documents/Photos", "SD-Karte/Lumen" … */
        fun folderLabel(dir: File): String {
            val internal = Environment.getExternalStorageDirectory().absolutePath
            val path = dir.absolutePath
            return when {
                path == internal -> "Interner Speicher"
                path.startsWith("$internal/") -> path.removePrefix("$internal/")
                path.startsWith("/storage/") -> "Externer Speicher/" + path.removePrefix("/storage/").substringAfter('/')
                else -> path
            }
        }

        /**
         * The folder behind a folder picked in the Android file picker. The backup works with plain
         * files (it must survive a reinstall), so only real storage – internal, SD card, USB stick –
         * can be used, not cloud providers.
         */
        fun folderOf(treeUri: Uri): File? {
            if (treeUri.authority != "com.android.externalstorage.documents") return null
            val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':', "")
            val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
            return if (rel.isEmpty()) base else File(base, rel)
        }

        private const val DB_NAME = "lumen.db"
        private const val SETTINGS_FILE = "settings.preferences_pb"
        private const val MANIFEST = "backup.json"
        private const val INSTALL_ID_FILE = "install_id"
        private const val READY_MARKER = "ready"
        private const val SOURCE_FILE = "source"
        /** Folder the last restore came from – future backups go there too. */
        private const val RESTORED_FROM_FILE = "restored_from"
        private const val SETTLE_MS = 45_000L
        private const val MAX_DELAY_MS = 10 * 60_000L
        private val TABLES = arrayOf("embeddings", "optimized", "faces", "face_scans", "persons", "face_rejections")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun hasAccess(): Boolean = Environment.isExternalStorageManager()

        fun accessIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

        /** True if the folder contains a backup (only readable with [hasAccess]). */
        fun backupExists(dir: File = defaultRoot): Boolean = hasAccess() && File(dir, MANIFEST).exists()

        private fun pendingDir(context: Context) = File(context.filesDir, "restore-pending")

        /**
         * Moves a staged restore into place. Runs in `Application.onCreate` before the database
         * and the settings store are opened. Returns true if a restore was applied.
         */
        fun applyPendingRestore(context: Context): Boolean {
            val dir = pendingDir(context)
            val marker = File(dir, READY_MARKER)
            if (!marker.exists()) {
                if (dir.exists()) dir.deleteRecursively()
                return false
            }
            val applied = runCatching {
                File(dir, DB_NAME).takeIf { it.exists() }?.let { db ->
                    val target = context.getDatabasePath(DB_NAME)
                    target.parentFile?.mkdirs()
                    listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
                    if (!db.renameTo(target)) db.copyTo(target, overwrite = true)
                }
                File(dir, SETTINGS_FILE).takeIf { it.exists() }?.let { f ->
                    val target = File(context.filesDir, "datastore/$SETTINGS_FILE")
                    target.parentFile?.mkdirs()
                    target.delete()
                    if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
                }
                // Adopt the identity of the backup so the next automatic backup may update it.
                File(context.filesDir, INSTALL_ID_FILE).writeText(marker.readText())
                File(dir, SOURCE_FILE).takeIf { it.exists() }?.copyTo(File(context.filesDir, RESTORED_FROM_FILE), overwrite = true)
            }.isSuccess
            dir.deleteRecursively()
            return applied
        }

        /** Restarts the process so database and settings are opened from the restored files. */
        fun restartApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
            Runtime.getRuntime().exit(0)
        }
    }
}
