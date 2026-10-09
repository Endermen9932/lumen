package app.lumen.photos.llm

import app.lumen.photos.ai.tokenizer.JsonStreamReader
import app.lumen.photos.ai.tokenizer.LongLongMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Byte-level BPE tokenizer of the Qwen 2.5 language models (GPT-2 style): NFC, Qwen's regex
 * pre-tokenisation, GPT-2 byte alphabet, ranked merges, and special tokens like `<|im_start|>`.
 * Loaded from the model's `tokenizer.json`, cached in a compact binary file.
 */
class QwenTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    /** key = (leftId shl 32 | rightId), value = (rank shl 32 | mergedId) */
    private val merges: LongLongMap,
    private val specials: Map<String, Int>,
) {
    private val idToToken: Array<String?> by lazy {
        val max = (vocab.values.maxOrNull() ?: 0).coerceAtLeast(specials.values.maxOrNull() ?: 0)
        arrayOfNulls<String>(max + 1).also { arr ->
            for ((k, v) in vocab) arr[v] = k
            for ((k, v) in specials) arr[v] = k
        }
    }
    private val specialIds: Set<Int> = specials.values.toSet()

    fun id(token: String): Int? = specials[token] ?: vocab[token]

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>(text.length / 3 + 8)
        var start = 0
        val m = SPECIAL.matcher(text)
        while (m.find()) {
            val sid = specials[m.group()] ?: continue
            if (m.start() > start) encodeOrdinary(text.substring(start, m.start()), out)
            out += sid
            start = m.end()
        }
        if (start < text.length) encodeOrdinary(text.substring(start), out)
        return out.toIntArray()
    }

    private fun encodeOrdinary(text: String, out: MutableList<Int>) {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val m = PATTERN.matcher(normalized)
        while (m.find()) {
            val bytes = m.group().toByteArray(Charsets.UTF_8)
            val symbols = ArrayList<Int>(bytes.size)
            for (b in bytes) symbols += vocab[BYTE_ENCODER[b.toInt() and 0xFF].toString()] ?: continue
            out.addAll(merge(symbols))
        }
    }

    private fun merge(symbols: ArrayList<Int>): List<Int> {
        while (symbols.size > 1) {
            var bestRank = Long.MAX_VALUE
            var bestIndex = -1
            var bestId = -1
            for (i in 0 until symbols.size - 1) {
                val v = merges.get(pairKey(symbols[i], symbols[i + 1]))
                if (v != Long.MIN_VALUE) {
                    val rank = v ushr 32
                    if (rank < bestRank) {
                        bestRank = rank
                        bestIndex = i
                        bestId = (v and 0xFFFFFFFFL).toInt()
                    }
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = bestId
            symbols.removeAt(bestIndex + 1)
        }
        return symbols
    }

    /** Text of the given ids; special tokens are left out. */
    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (id in specialIds) continue
            val token = idToToken.getOrNull(id) ?: continue
            for (ch in token) {
                val b = BYTE_DECODER[ch.code]
                if (b >= 0) bytes.write(b)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun writeCache(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(CACHE_MAGIC)
            out.writeInt(vocab.size)
            for ((k, v) in vocab) { out.writeUTF(k); out.writeInt(v) }
            out.writeInt(specials.size)
            for ((k, v) in specials) { out.writeUTF(k); out.writeInt(v) }
            out.writeInt(merges.size)
            merges.forEach { k, v -> out.writeLong(k); out.writeLong(v) }
        }
        tmp.renameTo(file)
    }

    companion object {
        private const val CACHE_MAGIC = 0x51574E31 // "QWN1"

        /** Qwen 2 pre-tokenizer (same as tokenizer.json). */
        private val PATTERN: Pattern = Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS,
        )
        private val SPECIAL: Pattern = Pattern.compile("<\\|[a-z_]+\\|>")

        /** GPT-2 byte to printable unicode character table. */
        private val BYTE_ENCODER: CharArray = CharArray(256).also { table ->
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            for (i in bs.indices) table[bs[i]] = cs[i].toChar()
        }
        private val BYTE_DECODER: IntArray = IntArray(512) { -1 }.also { d ->
            for (b in 0 until 256) d[BYTE_ENCODER[b].code] = b
        }

        private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

        fun load(tokenizerJson: File, cacheFile: File): QwenTokenizer {
            if (cacheFile.exists() && cacheFile.lastModified() >= tokenizerJson.lastModified()) {
                runCatching { return readCache(cacheFile) }
            }
            val t = InputStreamReader(FileInputStream(tokenizerJson), Charsets.UTF_8).use { parse(JsonStreamReader(it)) }
            runCatching { t.writeCache(cacheFile) }
            return t
        }

        private fun readCache(file: File): QwenTokenizer =
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                check(input.readInt() == CACHE_MAGIC) { "Bad cache" }
                val vocab = HashMap<String, Int>()
                repeat(input.readInt()) { vocab[input.readUTF()] = input.readInt() }
                val specials = HashMap<String, Int>()
                repeat(input.readInt()) { specials[input.readUTF()] = input.readInt() }
                val count = input.readInt()
                val merges = LongLongMap(count)
                repeat(count) { merges.put(input.readLong(), input.readLong()) }
                QwenTokenizer(vocab, merges, specials)
            }

        fun parse(reader: JsonStreamReader): QwenTokenizer {
            val vocab = HashMap<String, Int>(160_000)
            val specials = HashMap<String, Int>()
            val raw = ArrayList<Pair<String, String>>(160_000)
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "added_tokens" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var id = -1
                            var content: String? = null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> id = reader.nextInt()
                                    "content" -> content = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (content != null && id >= 0) specials[content] = id
                        }
                        reader.endArray()
                    }
                    "model" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "vocab" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        val token = reader.nextName()
                                        vocab[token] = reader.nextInt()
                                    }
                                    reader.endObject()
                                }
                                "merges" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        if (reader.peek() == JsonStreamReader.Token.BEGIN_ARRAY) {
                                            reader.beginArray()
                                            val a = reader.nextString()
                                            val b = reader.nextString()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                            raw += a to b
                                        } else {
                                            val s = reader.nextString()
                                            val sp = s.indexOf(' ', startIndex = 1)
                                            if (sp > 0) raw += s.substring(0, sp) to s.substring(sp + 1)
                                        }
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val merges = LongLongMap(raw.size)
            raw.forEachIndexed { rank, (a, b) ->
                val ia = vocab[a] ?: return@forEachIndexed
                val ib = vocab[b] ?: return@forEachIndexed
                val merged = vocab[a + b] ?: return@forEachIndexed
                val key = pairKey(ia, ib)
                if (merges.get(key) == Long.MIN_VALUE) merges.put(key, (rank.toLong() shl 32) or merged.toLong())
            }
            return QwenTokenizer(vocab, merges, specials)
        }
    }
}
