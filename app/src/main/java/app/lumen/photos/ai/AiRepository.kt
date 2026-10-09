package app.lumen.photos.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import app.lumen.photos.work.BackgroundJobs
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.lumen.photos.data.db.LumenDatabase
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.data.settings.SettingsRepository
import app.lumen.photos.work.IndexImportWorker
import app.lumen.photos.work.IndexWorker
import app.lumen.photos.work.ModelDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class IndexProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val msPerImage: Int,
    val modelId: String?,
    val state: WorkInfo.State,
    val attempts: Int,
)

/** Outcome of an import of a ".lumenindex" file made on the PC. */
data class ImportResult(
    val modelId: String,
    /** Photos that got a vector from the file. */
    val imported: Int,
    /** Photos in the file that already had a vector on the phone. */
    val alreadyIndexed: Int,
    /** Of [imported]: matched by name only because the file size had changed. */
    val byName: Int,
    val inFile: Int,
    val onPhone: Int,
    /** Photos on the phone that are not in the file (they are indexed on the phone as usual). */
    val unmatched: Int,
)

data class ImportStatus(
    val id: java.util.UUID,
    val running: Boolean,
    val done: Int,
    val total: Int,
    /** [IndexImportWorker.STAGE_MATCH] or [IndexImportWorker.STAGE_WRITE]. */
    val stage: Int,
    val result: ImportResult?,
    val error: String?,
)

data class SearchResult(
    val query: String,
    val items: List<MediaItem>,
    val scores: Map<Long, Float>,
    val tookMs: Long,
    val indexedCount: Int,
)

/** Facade for everything AI related: model selection, indexing jobs and semantic search. */
class AiRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    val models: ModelManager,
    private val db: LumenDatabase,
    val index: SearchIndex,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context)
    private val engineMutex = Mutex()
    private val textCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 64
    }

    val activeModel: StateFlow<AiModel?> = settings.settings
        .map { ModelCatalog.byId(it.activeModelId) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ModelCatalog.byId(settings.current.activeModelId))

    @OptIn(ExperimentalCoroutinesApi::class)
    val indexedCount: StateFlow<Int> = activeModel
        .flatMapLatest { m -> if (m == null) flowOf(0) else db.embeddings().countFlow(m.id) }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val indexProgress: Flow<IndexProgress?> = workManager.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME)
        .map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: return@map null
            IndexProgress(
                running = info.state == WorkInfo.State.RUNNING,
                done = info.progress.getInt(IndexWorker.KEY_DONE, 0),
                total = info.progress.getInt(IndexWorker.KEY_TOTAL, 0),
                msPerImage = info.progress.getInt(IndexWorker.KEY_MS, 0),
                modelId = info.progress.getString(IndexWorker.KEY_MODEL),
                state = info.state,
                attempts = info.runAttemptCount,
            )
        }

    private val seenImports = MutableStateFlow<Set<java.util.UUID>>(emptySet())

    /** Running import, or the finished one until the user dismisses its result. */
    val importStatus: Flow<ImportStatus?> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(IndexImportWorker.NAME),
        seenImports,
    ) { infos, seen ->
        val info = infos.firstOrNull { !it.state.isFinished }
            ?: infos.firstOrNull { it.state != WorkInfo.State.CANCELLED && it.id !in seen }
            ?: return@combine null
        val out = info.outputData
        ImportStatus(
            id = info.id,
            running = !info.state.isFinished,
            done = info.progress.getInt(IndexImportWorker.KEY_DONE, 0),
            total = info.progress.getInt(IndexImportWorker.KEY_TOTAL, 0),
            stage = info.progress.getInt(IndexImportWorker.KEY_STAGE, IndexImportWorker.STAGE_MATCH),
            result = if (info.state == WorkInfo.State.SUCCEEDED) ImportResult(
                modelId = out.getString(IndexImportWorker.KEY_MODEL) ?: "",
                imported = out.getInt(IndexImportWorker.KEY_IMPORTED, 0),
                alreadyIndexed = out.getInt(IndexImportWorker.KEY_SKIPPED, 0),
                byName = out.getInt(IndexImportWorker.KEY_BY_NAME, 0),
                inFile = out.getInt(IndexImportWorker.KEY_IN_FILE, 0),
                onPhone = out.getInt(IndexImportWorker.KEY_ON_PHONE, 0),
                unmatched = out.getInt(IndexImportWorker.KEY_UNMATCHED, 0),
            ) else null,
            error = if (info.state == WorkInfo.State.FAILED) out.getString(IndexImportWorker.KEY_ERROR) ?: "Import fehlgeschlagen." else null,
        )
    }

    /** Map of modelId to download progress (0..1) for running downloads. */
    val downloads: Flow<Map<String, Float>> = workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG)
        .map { infos ->
            infos.filter { !it.state.isFinished }.associate { info ->
                val id = info.tags.firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: ""
                val done = info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0)
                val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 1).coerceAtLeast(1)
                id to (done.toFloat() / total)
            }
        }

    val failedDownloads: Flow<Map<String, String>> = workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG)
        .map { infos ->
            infos.filter { it.state == WorkInfo.State.FAILED }.associate { info ->
                val id = info.tags.firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: ""
                id to (info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Unbekannter Fehler")
            }
        }

    @OptIn(FlowPreview::class)
    fun start() {
        // Keep the in-memory index in sync with the active model.
        activeModel.onEach { m ->
            if (m != null && models.isInstalled(m)) {
                index.ensureLoaded(m.id)
                if (settings.current.autoIndexNewMedia) scheduleIndexing()
            } else {
                index.invalidate()
            }
        }.launchIn(scope)

        // Index new photos automatically a little while after they appear.
        media.version.drop(1).debounce(15_000).onEach {
            val m = activeModel.value ?: return@onEach
            if (settings.current.autoIndexNewMedia && models.isInstalled(m)) scheduleIndexing()
        }.launchIn(scope)

        combine(activeModel, models.installed) { m, installed -> m to installed }
            .distinctUntilChanged()
            .onEach { (m, installed) ->
                if (m != null && m.id in installed) {
                    index.ensureLoaded(m.id)
                    if (settings.current.autoIndexNewMedia) scheduleIndexing()
                }
            }.launchIn(scope)
    }

    suspend fun setActiveModel(model: AiModel) {
        val changed = settings.current.activeModelId != model.id
        // Stop the job of the old model first – it must not keep writing with a closed engine.
        if (changed) cancelIndexing()
        settings.update { it.copy(activeModelId = model.id) }
        engineMutex.withLock {
            engineHolder?.engine?.close()
            engineHolder = null
            synchronized(textCache) { textCache.clear() }
        }
        if (changed && settings.current.autoIndexNewMedia && models.isInstalled(model)) scheduleIndexing(replace = true)
    }

    private class EngineHolder(val engine: EmbeddingEngine, val threads: Int, val xnnpack: Boolean)

    private var engineHolder: EngineHolder? = null

    /** Returns a shared engine for [model], recreating it if the thread settings changed. */
    suspend fun engine(model: AiModel): EmbeddingEngine = engineMutex.withLock {
        val s = settings.current
        val current = engineHolder
        if (current != null && current.engine.model == model && current.threads == s.aiThreads && current.xnnpack == s.useXnnpack) {
            return@withLock current.engine
        }
        current?.engine?.close()
        val fresh = EmbeddingEngine(model, models, s.aiThreads, s.useXnnpack)
        engineHolder = EngineHolder(fresh, s.aiThreads, s.useXnnpack)
        fresh
    }

    fun download(model: DownloadableModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.id))
            .addTag(ModelDownloadWorker.TAG)
            .addTag("model:${model.id}")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("download-${model.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(model: DownloadableModel) {
        workManager.cancelUniqueWork("download-${model.id}")
    }

    /** Starts (or keeps) the indexing job. Does nothing while the user has paused indexing. */
    fun scheduleIndexing(replace: Boolean = false, ignorePause: Boolean = false) {
        val s = settings.current
        if (s.indexPaused && !ignorePause) return
        workManager.enqueueUniqueWork(
            IndexWorker.NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            BackgroundJobs.request(IndexWorker::class.java, s.indexOnlyWhileCharging)
        )
    }

    /** Imports a ".lumenindex" file (made by the desktop app) in the background. */
    fun importIndex(uri: android.net.Uri) {
        val request = OneTimeWorkRequestBuilder<IndexImportWorker>()
            .setInputData(workDataOf(IndexImportWorker.KEY_URI to uri.toString()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(IndexImportWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun dismissImportResult(id: java.util.UUID) {
        seenImports.value = seenImports.value + id
    }

    /** The database changed behind the in-memory index's back: reload it if it shows [model]. */
    suspend fun onIndexImported(model: AiModel) {
        if (index.loadedModel == model.id) index.invalidate()
        if (activeModel.value == model && models.isInstalled(model)) index.ensureLoaded(model.id)
    }

    fun cancelIndexing() {
        workManager.cancelUniqueWork(IndexWorker.NAME)
    }

    /** Pauses until [resumeIndexing] – no new photos, app start or model download restarts it. */
    suspend fun pauseIndexing() {
        settings.update { it.copy(indexPaused = true) }
        cancelIndexing()
    }

    suspend fun resumeIndexing() {
        settings.update { it.copy(indexPaused = false) }
        scheduleIndexing(replace = true, ignorePause = true)
    }

    /**
     * Called when the app comes to the foreground: a job Android interrupted (or that is still
     * waiting for JobScheduler) is restarted right away, while the app may promote it to a
     * foreground service again.
     */
    suspend fun resumeIfStalled() {
        val s = settings.current
        val m = activeModel.value ?: return
        if (s.indexPaused || !models.isInstalled(m)) return
        val info = workManager.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME).first().firstOrNull { !it.state.isFinished }
        val indexable = media.media.value.count { s.indexVideos || it.isImage }
        when {
            info == null -> if (s.autoIndexNewMedia && indexable > 0 && indexedCount.value < indexable) scheduleIndexing()
            info.state == WorkInfo.State.ENQUEUED && !s.indexOnlyWhileCharging -> scheduleIndexing(replace = true)
        }
    }

    suspend fun clearIndex(model: AiModel) {
        if (activeModel.value == model) cancelIndexing()
        db.embeddings().deleteModel(model.id)
        if (index.loadedModel == model.id) index.invalidate()
    }

    suspend fun deleteModel(model: AiModel) {
        if (activeModel.value == model) {
            engineMutex.withLock {
                engineHolder?.engine?.close()
                engineHolder = null
            }
        }
        clearIndex(model)
        models.delete(model)
    }

    private suspend fun textEmbedding(model: AiModel, query: String): FloatArray {
        val key = "${model.id}|${query.trim().lowercase()}"
        synchronized(textCache) { textCache[key] }?.let { return it }
        val v = withContext(Dispatchers.Default) { engine(model).embedText(query) }
        synchronized(textCache) { textCache[key] = v }
        return v
    }

    fun warmUp() {
        val m = activeModel.value ?: return
        if (!models.isInstalled(m)) return
        scope.launch(Dispatchers.Default) { runCatching { engine(m).warmUpText() } }
    }

    suspend fun search(query: String): SearchResult? {
        val model = activeModel.value ?: return null
        if (!models.isInstalled(model) || query.isBlank()) return null
        val start = System.currentTimeMillis()
        index.ensureLoaded(model.id)
        val vector = textEmbedding(model, query)
        val scored = withContext(Dispatchers.Default) { index.search(vector, settings.current.searchStrictness) }
        return toResult(query, scored, start)
    }

    /**
     * Text search inside the media that pass the search filters ([allowed]): the same adaptive
     * cut-off as the normal search, so "Strand" + "Sommer 2026" shows beach photos of that
     * summer – not every photo of the summer.
     */
    suspend fun searchFiltered(query: String, allowed: Set<Long>, label: String): SearchResult? {
        val model = activeModel.value ?: return null
        if (!models.isInstalled(model) || query.isBlank()) return null
        val start = System.currentTimeMillis()
        index.ensureLoaded(model.id)
        val vector = textEmbedding(model, query)
        val scored = withContext(Dispatchers.Default) {
            index.search(vector, settings.current.searchStrictness, minResults = 6, allowed = allowed)
        }
        return toResult(label, scored, start)
    }

    /**
     * Search restricted to [allowed] media (e.g. all photos of "Paul"). Without an AI model or with
     * an empty [query] the photos are simply returned newest first.
     */
    suspend fun searchWithin(query: String, allowed: Set<Long>, label: String): SearchResult {
        val start = System.currentTimeMillis()
        val model = activeModel.value
        val byId = media.media.value.associateBy { it.id }
        if (query.isBlank() || model == null || !models.isInstalled(model)) {
            val items = allowed.mapNotNull { byId[it] }.sortedByDescending { it.timestamp }
            return SearchResult(label, items, emptyMap(), System.currentTimeMillis() - start, items.size)
        }
        index.ensureLoaded(model.id)
        val vector = textEmbedding(model, query)
        val (ids, scores) = withContext(Dispatchers.Default) { index.scoreAll(vector) }
        val scored = ids.indices.filter { ids[it] in allowed }.sortedByDescending { scores[it] }
        val items = scored.mapNotNull { byId[ids[it]] }
        // Photos without an AI vector yet go to the end.
        val rest = allowed.filter { id -> items.none { it.id == id } }.mapNotNull { byId[it] }
        return SearchResult(label, items + rest, scored.associate { ids[it] to scores[it] }, System.currentTimeMillis() - start, allowed.size)
    }

    suspend fun similar(item: MediaItem): SearchResult? {
        val model = activeModel.value ?: return null
        index.ensureLoaded(model.id)
        val start = System.currentTimeMillis()
        val scored = withContext(Dispatchers.Default) { index.similar(item.id, settings.current.searchStrictness) }
        return toResult(item.name, scored, start)
    }

    /** Ranks the given items against a text concept (used for "Entdecken" categories). */
    suspend fun bestMatch(query: String): MediaItem? {
        val model = activeModel.value ?: return null
        if (!models.isInstalled(model)) return null
        index.ensureLoaded(model.id)
        val vector = textEmbedding(model, query)
        val top = withContext(Dispatchers.Default) { index.search(vector, 99f, minResults = 1, maxResults = 1) }
        return top.firstOrNull()?.let { media.byId(it.id) }
    }

    private fun toResult(query: String, scored: List<ScoredId>, start: Long): SearchResult {
        val byId = media.media.value.associateBy { it.id }
        val items = scored.mapNotNull { byId[it.id] }
        return SearchResult(
            query = query,
            items = items,
            scores = scored.associate { it.id to it.score },
            tookMs = System.currentTimeMillis() - start,
            indexedCount = index.size.value,
        )
    }
}
