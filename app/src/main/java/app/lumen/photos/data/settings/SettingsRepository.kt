package app.lumen.photos.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class TargetResolution(val label: String, val longEdge: Int, val shortEdge: Int) {
    HD("HD · 1280 × 720", 1280, 720),
    FHD("Full HD · 1920 × 1080", 1920, 1080),
    QHD("QHD · 2560 × 1440", 2560, 1440),
    UHD("4K · 3840 × 2160", 3840, 2160),
    ORIGINAL("Originalauflösung", Int.MAX_VALUE, Int.MAX_VALUE),
}

enum class OutputFormat(val label: String, val mime: String, val extension: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    WEBP("WebP", "image/webp", "webp"),
}

enum class OptimizeMode { REPLACE, COPY_AND_TRASH }

@Serializable
data class OptimizerSettings(
    val resolution: TargetResolution = TargetResolution.FHD,
    val quality: Int = 85,
    val format: OutputFormat = OutputFormat.JPEG,
    val mode: OptimizeMode = OptimizeMode.REPLACE,
    val keepUltraHdr: Boolean = true,
    val keepMetadata: Boolean = true,
    val skipFavorites: Boolean = false,
    val includeOtherFormats: Boolean = false,
    /** Also re-encode images that are already within the target resolution. */
    val recompressSmaller: Boolean = false,
    /** Only process files where the new file is at least this much smaller (percent). */
    val minSavingsPercent: Int = 10,
)

enum class VideoCodec(val label: String) { HEVC("HEVC (H.265)"), H264("H.264") }

@Serializable
data class VideoOptimizerSettings(
    /** Uses the short edge: FHD = 1080p, QHD = 1440p, UHD = 2160p. */
    val resolution: TargetResolution = TargetResolution.FHD,
    val quality: Int = 70,
    val codec: VideoCodec = VideoCodec.HEVC,
    val mode: OptimizeMode = OptimizeMode.REPLACE,
    val keepHdr: Boolean = true,
    val keepMetadata: Boolean = true,
    val skipFavorites: Boolean = false,
    val minSavingsPercent: Int = 15,
)

@Serializable
data class BackupSettings(
    /** Persisted SAF tree URI of the backup destination (USB stick, SD card, cloud provider …). */
    val treeUri: String? = null,
    val folderName: String? = null,
    val includeVideos: Boolean = true,
    val autoBackup: Boolean = false,
    val lastBackupAt: Long = 0,
    val lastBackupCopied: Int = 0,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val seedColor: Long = 0xFF6750A4,
    val gridColumns: Int = 4,
    val showVideosInTimeline: Boolean = true,
    val showMemories: Boolean = true,
    val reduceMotion: Boolean = false,
    /** Screens and photos follow the finger during the Android back gesture. */
    val backGestureAnimations: Boolean = true,
    val activeModelId: String? = null,
    val activeFaceModelId: String? = null,
    val aiThreads: Int = 6,
    val useXnnpack: Boolean = false,
    val indexOnlyWhileCharging: Boolean = false,
    /** Paused by the user – nothing restarts the job until they resume it. */
    val indexPaused: Boolean = false,
    val facesPaused: Boolean = false,
    val autoIndexNewMedia: Boolean = true,
    val indexVideos: Boolean = true,
    /** Keep the display on (dimmed) while indexing or optimising. */
    val keepScreenOnDuringWork: Boolean = true,
    /** Higher = fewer, more precise results. Standard deviations above the mean score. */
    val searchStrictness: Float = 2.2f,
    val onboardingDone: Boolean = false,
    /** Mirrors models, indexes and settings to Documents/Photos so a reinstall loses nothing. */
    val developerMode: Boolean = false,
    /** Folder of the developer backup; null = Documents/Photos. */
    val devBackupPath: String? = null,
    val optimizer: OptimizerSettings = OptimizerSettings(),
    val backup: BackupSettings = BackupSettings(),
    val videoOptimizer: VideoOptimizerSettings = VideoOptimizerSettings(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("app_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    /** Loaded synchronously once so the first frame already uses the right theme. */
    private val initial: AppSettings = runBlocking { decode(context.dataStore.data.first()[key]) }

    private val state = MutableStateFlow(initial)
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    init {
        context.dataStore.data.map { decode(it[key]) }.onEach { state.value = it }.launchIn(scope)
    }

    val current: AppSettings get() = settings.value

    /** Once this returns, [current] already contains the change (jobs scheduled next rely on it). */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val saved = context.dataStore.edit { prefs ->
            val next = transform(decode(prefs[key]))
            prefs[key] = json.encodeToString(AppSettings.serializer(), next)
        }
        state.value = decode(saved[key])
    }

    suspend fun updateVideoOptimizer(transform: (VideoOptimizerSettings) -> VideoOptimizerSettings) =
        update { it.copy(videoOptimizer = transform(it.videoOptimizer)) }

    suspend fun updateBackup(transform: (BackupSettings) -> BackupSettings) =
        update { it.copy(backup = transform(it.backup)) }

    suspend fun updateOptimizer(transform: (OptimizerSettings) -> OptimizerSettings) =
        update { it.copy(optimizer = transform(it.optimizer)) }
}
