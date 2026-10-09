package app.lumen.photos.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Greedy text generation with a decoder-only language model exported by Optimum / transformers.js
 * (inputs `input_ids`, `attention_mask`, `position_ids`, `past_key_values.N.key|value`; outputs
 * `logits`, `present.N.key|value`).
 *
 * The prompt is processed in small chunks, so the logits of a long prompt (tokens × 152k floats)
 * never have to fit in memory at once, and the key/value cache of a fixed prompt beginning (the
 * instructions with the examples) is kept: a new query only has to process its own few tokens.
 */
class LlmEngine(modelFile: File, val tokenizer: QwenTokenizer, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads.coerceIn(1, 8))
        },
    )
    private val layers: Int
    private val kvHeads: Long
    private val headDim: Long
    private val hasPositionIds: Boolean
    private val outputNames: List<String> = session.outputNames.toList()
    private val stopIds: Set<Int> = listOfNotNull(tokenizer.id("<|im_end|>"), tokenizer.id("<|endoftext|>")).toSet()

    init {
        val keyInputs = session.inputInfo.filterKeys { it.startsWith("past_key_values.") && it.endsWith(".key") }
        layers = keyInputs.size
        val shape = (keyInputs.values.first().info as TensorInfo).shape
        kvHeads = shape[1]
        headDim = shape[3]
        hasPositionIds = "position_ids" in session.inputInfo
    }

    /** Key/value cache after a sequence of [length] tokens. Owns its tensors. */
    private class Cache(val tensors: Map<String, OnnxTensor>, val length: Int, private val owner: OrtSession.Result?) : AutoCloseable {
        override fun close() {
            if (owner != null) owner.close() else tensors.values.forEach { it.close() }
        }
    }

    private var prefixText: String? = null
    private var prefixCache: Cache? = null
    private var prefixLastLogits: FloatArray? = null

    private fun emptyCache(): Cache {
        val empty = FloatBuffer.allocate(0)
        val tensors = HashMap<String, OnnxTensor>()
        for (i in 0 until layers) for (kind in KINDS) {
            tensors["past_key_values.$i.$kind"] = OnnxTensor.createTensor(env, empty, longArrayOf(1, kvHeads, 0, headDim))
        }
        return Cache(tensors, 0, null)
    }

    /**
     * Runs [ids] through the model on top of [cache]. Returns the new cache and the logits of the
     * last token. [cache] is only closed if [closeInput] (the cached prompt beginning stays alive).
     */
    private fun forward(ids: IntArray, cache: Cache, closeInput: Boolean): Pair<Cache, FloatArray> {
        var current = cache
        var ownsCurrent = closeInput
        var logits = FloatArray(0)
        var offset = 0
        while (offset < ids.size) {
            val n = minOf(CHUNK, ids.size - offset)
            val past = current.length
            val inputs = HashMap<String, OnnxTensor>(current.tensors)
            val created = ArrayList<OnnxTensor>(3)
            fun add(name: String, data: LongArray, shape: LongArray) {
                val t = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
                created += t
                inputs[name] = t
            }
            add("input_ids", LongArray(n) { ids[offset + it].toLong() }, longArrayOf(1, n.toLong()))
            add("attention_mask", LongArray(past + n) { 1L }, longArrayOf(1, (past + n).toLong()))
            if (hasPositionIds) add("position_ids", LongArray(n) { (past + it).toLong() }, longArrayOf(1, n.toLong()))
            val result = try {
                session.run(inputs)
            } finally {
                created.forEach { it.close() }
            }
            val presents = HashMap<String, OnnxTensor>()
            for ((i, name) in outputNames.withIndex()) {
                if (name.startsWith("present.")) presents[name.replace("present.", "past_key_values.")] = result.get(i) as OnnxTensor
            }
            val logitTensor = result.get(outputNames.indexOf("logits")) as OnnxTensor
            val fb = logitTensor.floatBuffer
            val vocab = fb.capacity() / n
            logits = FloatArray(vocab)
            fb.position((n - 1) * vocab)
            fb.get(logits)
            if (ownsCurrent) current.close()
            current = Cache(presents, past + n, result)
            ownsCurrent = true
            offset += n
        }
        return current to logits
    }

    /**
     * Generates the answer to [prefix] + [suffix] greedily, at most [maxTokens] tokens, until the
     * end-of-turn token or until [done] says the text is complete (e.g. a closed JSON object).
     */
    @Synchronized
    fun generate(prefix: String, suffix: String, maxTokens: Int, isCancelled: () -> Boolean = { false }, done: (String) -> Boolean = { false }): String {
        if (prefixText != prefix || prefixCache == null) {
            prefixCache?.close()
            prefixCache = null
            val (cache, logits) = forward(tokenizer.encode(prefix), emptyCache(), closeInput = true)
            prefixCache = cache
            prefixLastLogits = logits
            prefixText = prefix
        }
        val base = prefixCache!!
        val suffixIds = tokenizer.encode(suffix)
        var (cache, logits) = if (suffixIds.isEmpty()) base to prefixLastLogits!! else forward(suffixIds, base, closeInput = false)
        val out = ArrayList<Int>()
        try {
            while (out.size < maxTokens && !isCancelled()) {
                val next = argmax(logits)
                if (next in stopIds) break
                out += next
                if (done(tokenizer.decode(out))) break
                val step = forward(intArrayOf(next), cache, closeInput = cache !== base)
                cache = step.first
                logits = step.second
            }
        } finally {
            if (cache !== base) cache.close()
        }
        return tokenizer.decode(out)
    }

    private fun argmax(v: FloatArray): Int {
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in v.indices) if (v[i] > bestV) { bestV = v[i]; best = i }
        return best
    }

    @Synchronized
    override fun close() {
        prefixCache?.close()
        prefixCache = null
        session.close()
    }

    private companion object {
        val KINDS = arrayOf("key", "value")
        const val CHUNK = 32
    }
}
