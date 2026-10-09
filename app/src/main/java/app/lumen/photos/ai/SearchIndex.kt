package app.lumen.photos.ai

import app.lumen.photos.data.db.EmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.sqrt

data class ScoredId(val id: Long, val score: Float)

/**
 * In-memory vector index of all image embeddings of the active model. Vectors live in off-heap
 * memory (a direct buffer) so even 100k photos with 1536-dim embeddings don't stress the Java heap.
 * Search is an exact brute-force dot product which takes well under 100 ms on a Tensor G5.
 */
class SearchIndex(private val dao: EmbeddingDao) {

    private val lock = ReentrantReadWriteLock()
    private val loadMutex = Mutex()

    private var modelId: String? = null
    private var dim = 0
    private var count = 0
    private var ids = LongArray(0)
    private var vectors: FloatBuffer = FloatBuffer.allocate(0)
    private val positions = HashMap<Long, Int>()

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    val loadedModel: String? get() = modelId

    suspend fun ensureLoaded(model: String) = loadMutex.withLock {
        if (modelId == model) return@withLock
        withContext(Dispatchers.IO) {
            val newIds = ArrayList<Long>()
            var buffer: FloatBuffer? = null
            var d = 0
            var n = 0
            var offset = 0
            while (true) {
                val page = dao.page(model, 2000, offset)
                if (page.isEmpty()) break
                offset += page.size
                for (e in page) {
                    val v = Fp16.decode(e.vector)
                    if (d == 0) d = v.size
                    if (v.size != d) continue
                    if (buffer == null || buffer.capacity() < (n + 1) * d) {
                        buffer = grow(buffer, maxOf(1024, n * 2) * d, n * d)
                    }
                    buffer.position(n * d)
                    buffer.put(v)
                    newIds += e.mediaId
                    n++
                }
            }
            lock.write {
                modelId = model
                dim = d
                count = n
                ids = newIds.toLongArray()
                vectors = buffer ?: FloatBuffer.allocate(0)
                positions.clear()
                for (i in 0 until n) positions[ids[i]] = i
            }
            _size.value = n
        }
    }

    fun invalidate() = lock.write {
        modelId = null
        count = 0
        positions.clear()
        _size.value = 0
    }

    private fun grow(old: FloatBuffer?, capacity: Int, used: Int): FloatBuffer {
        val fresh = ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (old != null && used > 0) {
            val dup = old.duplicate()
            dup.position(0)
            dup.limit(used)
            fresh.put(dup)
        }
        return fresh
    }

    /** Adds or replaces a vector for the currently loaded model (called by the indexer). */
    fun put(model: String, id: Long, vector: FloatArray) = lock.write {
        if (model != modelId) return@write
        if (dim == 0) dim = vector.size
        if (vector.size != dim) return@write
        val existing = positions[id]
        val pos = existing ?: count
        if (existing == null) {
            if (vectors.capacity() < (count + 1) * dim) vectors = grow(vectors, maxOf(1024, count * 2) * dim, count * dim)
            if (ids.size <= count) ids = ids.copyOf(maxOf(1024, count * 2))
            ids[count] = id
            positions[id] = count
            count++
        }
        vectors.position(pos * dim)
        vectors.put(vector)
        _size.value = count
    }

    fun remove(model: String, removed: Collection<Long>) = lock.write {
        if (model != modelId || removed.isEmpty()) return@write
        // Compact by moving the last element into the freed slot.
        for (id in removed) {
            val pos = positions.remove(id) ?: continue
            val last = count - 1
            if (pos != last) {
                val tmp = FloatArray(dim)
                vectors.position(last * dim); vectors.get(tmp)
                vectors.position(pos * dim); vectors.put(tmp)
                ids[pos] = ids[last]
                positions[ids[pos]] = pos
            }
            count--
        }
        _size.value = count
    }

    fun contains(id: Long): Boolean = lock.read { positions.containsKey(id) }

    fun vectorOf(id: Long): FloatArray? = lock.read {
        val pos = positions[id] ?: return@read null
        FloatArray(dim).also { out ->
            val dup = vectors.duplicate()
            dup.position(pos * dim)
            dup.get(out)
        }
    }

    /** Scores every indexed item against [query]. */
    fun scoreAll(query: FloatArray): Pair<LongArray, FloatArray> = lock.read {
        if (query.size != dim || count == 0) return@read LongArray(0) to FloatArray(0)
        val scores = FloatArray(count)
        val v = vectors
        val d = dim
        for (i in 0 until count) {
            var s = 0f
            val base = i * d
            var j = 0
            while (j < d) {
                s += v.get(base + j) * query[j]
                j++
            }
            scores[i] = s
        }
        ids.copyOf(count) to scores
    }

    /**
     * Returns the best matches. Results are cut off adaptively: everything that stands out by more
     * than [strictness] standard deviations from the average score, at least [minResults].
     */
    fun search(
        query: FloatArray,
        strictness: Float,
        minResults: Int = 12,
        maxResults: Int = 800,
        exclude: Long? = null,
        /** Only these media may be returned (search filters); the cut-off still uses all scores. */
        allowed: Set<Long>? = null,
    ): List<ScoredId> {
        val (allIds, scores) = scoreAll(query)
        if (scores.isEmpty()) return emptyList()
        var mean = 0.0
        for (s in scores) mean += s
        mean /= scores.size
        var variance = 0.0
        for (s in scores) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / scores.size).coerceAtLeast(1e-6)
        val threshold = (mean + strictness * std).toFloat()

        val order = scores.indices.sortedByDescending { scores[it] }
        val result = ArrayList<ScoredId>()
        for (i in order) {
            if (allIds[i] == exclude) continue
            if (allowed != null && allIds[i] !in allowed) continue
            if (result.size >= maxResults) break
            if (result.size >= minResults && scores[i] < threshold) break
            result += ScoredId(allIds[i], scores[i])
        }
        return result
    }

    fun similar(id: Long, strictness: Float): List<ScoredId> {
        val v = vectorOf(id) ?: return emptyList()
        return search(v, strictness + 0.6f, minResults = 12, maxResults = 300, exclude = id)
    }

    /**
     * Groups visually near-identical photos (bursts, retakes). Only compares photos taken within
     * [windowMs] of each other, which keeps it fast for large libraries.
     */
    fun nearDuplicateGroups(
        itemsByTime: List<Pair<Long, Long>>,
        threshold: Float,
        windowMs: Long = 20 * 60 * 1000L,
    ): List<List<Long>> = lock.read {
        val present = itemsByTime.filter { positions.containsKey(it.first) }.sortedBy { it.second }
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            var c = x
            while (c != r) {
                val next = parent[c] ?: r
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: Long, b: Long) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val d = dim
        val v = vectors
        for (i in present.indices) {
            val (idA, tA) = present[i]
            parent.putIfAbsent(idA, idA)
            val pa = positions[idA]!! * d
            var j = i + 1
            while (j < present.size && present[j].second - tA <= windowMs) {
                val idB = present[j].first
                val pb = positions[idB]!! * d
                var s = 0f
                for (k in 0 until d) s += v.get(pa + k) * v.get(pb + k)
                if (s >= threshold) {
                    parent.putIfAbsent(idB, idB)
                    union(idA, idB)
                }
                j++
            }
        }
        present.map { it.first }
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
    }
}
