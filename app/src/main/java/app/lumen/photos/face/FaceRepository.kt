package app.lumen.photos.face

import android.content.Context
import androidx.work.ExistingWorkPolicy
import app.lumen.photos.work.BackgroundJobs
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lumen.photos.ai.EmbeddingEngine
import app.lumen.photos.ai.Fp16
import app.lumen.photos.ai.ModelManager
import app.lumen.photos.data.db.FaceDao
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.data.db.FaceRejectionEntity
import app.lumen.photos.data.db.PersonEntity
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.data.settings.SettingsRepository
import app.lumen.photos.work.FaceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A person as shown in the UI. */
data class Person(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val coverFace: Long?,
    val mediaIds: List<Long>,
) {
    val displayName: String get() = name ?: "Unbekannt"
}

data class FaceProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val msPerImage: Int,
    val state: WorkInfo.State,
    val attempts: Int,
)

/** A face shown on a swipe card. */
data class ReviewCandidate(val face: FaceEntity, val item: MediaItem, val similarity: Float, val alreadyAssigned: Boolean)

/**
 * Faces → persons. New faces join the person whose average face ("centroid") is most similar,
 * otherwise they start a new, still unnamed person. Names stick to persons, so re-scanned or
 * re-encoded photos find their way back to the right name automatically.
 */
class FaceRepository(
    private val context: Context,
    private val dao: FaceDao,
    private val settings: SettingsRepository,
    private val models: ModelManager,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context)
    private val mutex = Mutex()

    val activeModel: StateFlow<FaceModel?> = settings.settings
        .map { FaceModelCatalog.byId(it.activeFaceModelId) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, FaceModelCatalog.byId(settings.current.activeFaceModelId))

    /** Persons with at least [MIN_PHOTOS] photos (or a name), most photos first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val persons: StateFlow<List<Person>> = activeModel
        .flatMapLatest { m ->
            if (m == null) flowOf(emptyList())
            else combine(dao.personsFlow(m.id), dao.personMediaFlow(m.id), media.media) { persons, links, all ->
                val existing = all.mapTo(HashSet()) { it.id }
                val byPerson = links.filter { it.mediaId in existing }.groupBy({ it.personId }, { it.mediaId })
                persons.map { p -> Person(p.id, p.name, p.hidden, p.coverFaceId, byPerson[p.id].orEmpty()) }
                    .filter { it.mediaIds.size >= MIN_PHOTOS || it.name != null }
                    .sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.mediaIds.size })
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val scannedCount: StateFlow<Int> = activeModel
        .flatMapLatest { m -> if (m == null) flowOf(0) else dao.scanCount(m.id) }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val progress: Flow<FaceProgress?> = workManager.getWorkInfosForUniqueWorkFlow(FaceWorker.NAME).map { infos ->
        val info = infos.firstOrNull { !it.state.isFinished } ?: return@map null
        FaceProgress(
            info.state == WorkInfo.State.RUNNING,
            info.progress.getInt(FaceWorker.KEY_DONE, 0),
            info.progress.getInt(FaceWorker.KEY_TOTAL, 0),
            info.progress.getInt(FaceWorker.KEY_MS, 0),
            info.state,
            info.runAttemptCount,
        )
    }

    @OptIn(FlowPreview::class)
    fun start() {
        combine(activeModel, models.installed) { m, installed -> m?.takeIf { it.id in installed } }
            .distinctUntilChanged()
            .onEach { if (it != null && settings.current.autoIndexNewMedia) schedule() }
            .launchIn(scope)
        media.version.drop(1).debounce(20_000).onEach {
            val m = activeModel.value ?: return@onEach
            if (settings.current.autoIndexNewMedia && models.isInstalled(m)) schedule()
        }.launchIn(scope)
    }

    /** Starts (or keeps) the face scan. Does nothing while the user has paused it. */
    fun schedule(replace: Boolean = false, ignorePause: Boolean = false) {
        val s = settings.current
        if (s.facesPaused && !ignorePause) return
        workManager.enqueueUniqueWork(
            FaceWorker.NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            BackgroundJobs.request(FaceWorker::class.java, s.indexOnlyWhileCharging)
        )
    }

    fun cancel() = workManager.cancelUniqueWork(FaceWorker.NAME)

    suspend fun pause() {
        settings.update { it.copy(facesPaused = true) }
        cancel()
    }

    /** Resumes a paused scan, or starts one ("Jetzt scannen"). Already scanned photos are skipped. */
    suspend fun resume() {
        settings.update { it.copy(facesPaused = false) }
        schedule(replace = true, ignorePause = true)
    }

    /** Restarts a scan Android interrupted as soon as the app is in the foreground again. */
    suspend fun resumeIfStalled() {
        val s = settings.current
        val m = activeModel.value ?: return
        if (s.facesPaused || !models.isInstalled(m)) return
        val info = workManager.getWorkInfosForUniqueWorkFlow(FaceWorker.NAME).first().firstOrNull { !it.state.isFinished }
        val photos = media.media.value.count { it.isImage }
        when {
            info == null -> if (s.autoIndexNewMedia && photos > 0 && scannedCount.value < photos) schedule()
            info.state == WorkInfo.State.ENQUEUED && !s.indexOnlyWhileCharging -> schedule(replace = true)
        }
    }

    suspend fun setActiveModel(model: FaceModel) {
        val changed = settings.current.activeFaceModelId != model.id
        // The running scan belongs to the old model – stop it before switching.
        if (changed) cancel()
        settings.update { it.copy(activeFaceModelId = model.id) }
    }

    // ------------------------------------------------------------------ clustering

    private class Cluster(val id: Long, var centroid: FloatArray, var count: Int, val named: Boolean, val rejected: Set<Long>)

    private suspend fun loadClusters(modelId: String): MutableList<Cluster> {
        val rejections = dao.allRejections().groupBy({ it.personId }, { it.faceId })
        return dao.persons(modelId).map {
            Cluster(it.id, Fp16.decode(it.centroid), it.faceCount, it.name != null, rejections[it.id].orEmpty().toSet())
        }.toMutableList()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * Assigns faces without a person. Called by the worker after each batch and by "Neu gruppieren".
     */
    suspend fun assignUnassigned(model: FaceModel) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val clusters = loadClusters(model.id)
            val faces = dao.unassigned(model.id)
            val changed = HashSet<Long>()
            for (face in faces) {
                val v = Fp16.decode(face.embedding)
                var best: Cluster? = null
                var bestSim = -1f
                for (c in clusters) {
                    if (face.id in c.rejected) continue
                    val s = dot(v, c.centroid)
                    if (s > bestSim) { bestSim = s; best = c }
                }
                if (best != null && bestSim >= model.assignThreshold) {
                    dao.assign(face.id, best.id, false)
                    updateCentroid(best, v)
                    changed += best.id
                } else {
                    val id = dao.insertPerson(PersonEntity(modelId = model.id, centroid = Fp16.encode(v), faceCount = 1, coverFaceId = face.id))
                    dao.assign(face.id, id, false)
                    clusters += Cluster(id, v.copyOf(), 1, false, emptySet())
                }
            }
            for (c in clusters.filter { it.id in changed }) persistCluster(c)
        }
    }

    private fun updateCentroid(c: Cluster, v: FloatArray) {
        val n = c.count
        val next = FloatArray(v.size) { (c.centroid[it] * n + v[it]) / (n + 1) }
        c.centroid = EmbeddingEngine.normalize(next)
        c.count = n + 1
    }

    private suspend fun persistCluster(c: Cluster) {
        val p = dao.person(c.id) ?: return
        dao.upsertPerson(PersonEntity(p.id, p.modelId, p.name, p.hidden, Fp16.encode(c.centroid), c.count, p.coverFaceId))
    }

    /** Recomputes centroid, count and cover face of a person from its faces. */
    suspend fun refreshPerson(personId: Long) {
        val p = dao.person(personId) ?: return
        val faces = dao.facesOfPerson(personId)
        if (faces.isEmpty()) {
            if (p.name == null) dao.deletePerson(personId)
            else dao.upsertPerson(PersonEntity(p.id, p.modelId, p.name, p.hidden, p.centroid, 0, null))
            return
        }
        val dim = Fp16.decode(faces.first().embedding).size
        val sum = FloatArray(dim)
        // Confirmed faces count double – they are known to be right.
        for (f in faces) {
            val v = Fp16.decode(f.embedding)
            val w = if (f.confirmed) 2f else 1f
            for (i in 0 until dim) sum[i] += v[i] * w
        }
        val centroid = EmbeddingEngine.normalize(sum)
        val cover = faces.maxByOrNull { (if (it.confirmed) 1f else 0f) + it.score + (it.right - it.left) }?.id
        val keepCover = p.coverFaceId?.takeIf { id -> faces.any { it.id == id } }
        dao.upsertPerson(PersonEntity(p.id, p.modelId, p.name, p.hidden, Fp16.encode(centroid), faces.size, keepCover ?: cover))
    }

    /** Throws away all automatic groupings (names and confirmed faces stay) and groups again. */
    suspend fun regroup() {
        val model = activeModel.value ?: return
        mutex.withLock {
            dao.clearUnconfirmed(model.id)
            dao.deleteUnnamedPersons(model.id)
            dao.persons(model.id).forEach { refreshPerson(it.id) }
        }
        assignUnassigned(model)
    }

    // ------------------------------------------------------------------ user actions

    suspend fun rename(personId: Long, name: String?) {
        val p = dao.person(personId) ?: return
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        // Naming a person like an existing one merges them.
        val twin = clean?.let { n -> dao.persons(p.modelId).firstOrNull { it.id != p.id && it.name.equals(n, ignoreCase = true) } }
        if (twin != null) {
            merge(keep = twin.id, remove = p.id)
            return
        }
        dao.upsertPerson(PersonEntity(p.id, p.modelId, clean, p.hidden, p.centroid, p.faceCount, p.coverFaceId))
    }

    suspend fun setHidden(personId: Long, hidden: Boolean) {
        val p = dao.person(personId) ?: return
        dao.upsertPerson(PersonEntity(p.id, p.modelId, p.name, hidden, p.centroid, p.faceCount, p.coverFaceId))
    }

    /** Hides (or shows again) several persons at once. */
    suspend fun setHidden(personIds: Collection<Long>, hidden: Boolean) {
        if (personIds.isEmpty()) return
        // SQLite limits the number of bound variables, so very large selections go in chunks.
        personIds.chunked(500).forEach { dao.setHidden(it, hidden) }
    }

    suspend fun setCover(personId: Long, faceId: Long) {
        val p = dao.person(personId) ?: return
        dao.upsertPerson(PersonEntity(p.id, p.modelId, p.name, p.hidden, p.centroid, p.faceCount, faceId))
    }

    suspend fun merge(keep: Long, remove: Long) = mutex.withLock {
        if (keep == remove) return@withLock
        val kept = dao.person(keep) ?: return@withLock
        val removed = dao.person(remove)
        dao.moveFaces(remove, keep)
        dao.moveRejections(remove, keep)
        dao.deletePerson(remove)
        if (kept.name == null && removed?.name != null) {
            dao.upsertPerson(PersonEntity(kept.id, kept.modelId, removed.name, kept.hidden, kept.centroid, kept.faceCount, kept.coverFaceId))
        }
        refreshPerson(keep)
    }

    /** Merges several persons into [keep] at once (multi-select in the Personen tab). */
    suspend fun mergeAll(keep: Long, others: Collection<Long>) {
        for (id in others) if (id != keep) merge(keep = keep, remove = id)
    }

    /** Swipe right: this face really is the person. */
    suspend fun confirm(face: FaceEntity, personId: Long) {
        val old = face.personId
        dao.assign(face.id, personId, true)
        refreshPerson(personId)
        if (old != null && old != personId) refreshPerson(old)
    }

    /** Swipe left: this face is not the person – remove it and never suggest it again. */
    suspend fun rejectFace(face: FaceEntity, personId: Long) {
        dao.reject(FaceRejectionEntity(face.id, personId))
        if (face.personId == personId) {
            dao.assign(face.id, null, false)
            refreshPerson(personId)
            activeModel.value?.let { assignUnassigned(it) }
        }
    }

    /** Reverts a swipe: restores the face's previous assignment. */
    suspend fun undo(original: FaceEntity, personId: Long, wasYes: Boolean) {
        if (!wasYes) dao.unreject(original.id, personId)
        dao.assign(original.id, original.personId, original.confirmed)
        refreshPerson(personId)
        original.personId?.takeIf { it != personId }?.let { refreshPerson(it) }
    }

    /**
     * Cards for the swipe review: the person's least certain unconfirmed faces first, then faces
     * of other/unnamed groups that look similar ("Ist das Paul?").
     */
    suspend fun reviewCandidates(personId: Long, limit: Int = 50): List<ReviewCandidate> = withContext(Dispatchers.Default) {
        val model = activeModel.value ?: return@withContext emptyList()
        val person = dao.person(personId) ?: return@withContext emptyList()
        val centroid = Fp16.decode(person.centroid)
        val rejected = dao.rejectedFaces(personId).toSet()
        val byId = media.media.value.associateBy { it.id }
        val all = dao.allFaces(model.id)
        val own = all.filter { it.personId == personId && !it.confirmed }
            .map { it to dot(Fp16.decode(it.embedding), centroid) }
            .sortedBy { it.second }
        val lower = model.assignThreshold - 0.14f
        val suggestions = all.filter { it.personId != personId && !it.confirmed && it.id !in rejected }
            .map { it to dot(Fp16.decode(it.embedding), centroid) }
            .filter { it.second >= lower }
            .sortedByDescending { it.second }
        // Interleave uncertain own faces with suggestions so both get reviewed.
        val result = ArrayList<ReviewCandidate>()
        val a = own.iterator(); val b = suggestions.iterator()
        while (result.size < limit && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) a.next().let { (f, s) -> byId[f.mediaId]?.let { result += ReviewCandidate(f, it, s, true) } }
            if (result.size < limit && b.hasNext()) b.next().let { (f, s) -> byId[f.mediaId]?.let { result += ReviewCandidate(f, it, s, false) } }
        }
        result
    }

    suspend fun face(id: Long) = dao.face(id)
    fun personFlow(id: Long) = dao.personFlow(id)
    suspend fun facesOfPerson(id: Long) = dao.facesOfPerson(id)

    suspend fun resetModel(model: FaceModel) {
        if (activeModel.value == model) cancel()
        dao.deleteAllFaces(model.id)
        dao.deleteAllScans(model.id)
        dao.deleteAllPersons(model.id)
    }

    // ------------------------------------------------------------------ search

    /** Persons whose name appears in the query, with the rest of the query. */
    fun matchQuery(query: String): Pair<List<Person>, String> {
        var rest = " ${query.trim()} "
        val found = ArrayList<Person>()
        for (p in persons.value.filter { it.name != null }.sortedByDescending { it.name!!.length }) {
            val regex = Regex("(?i)(?<=\\s|^)${Regex.escape(p.name!!)}(?=\\s|$|[,.!?])")
            if (regex.containsMatchIn(rest)) {
                found += p
                rest = regex.replace(rest, " ")
            }
        }
        val cleaned = rest.replace(Regex("(?i)\\b(und|and|mit|with|&)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return found to cleaned
    }

    companion object {
        const val MIN_PHOTOS = 3
    }
}
