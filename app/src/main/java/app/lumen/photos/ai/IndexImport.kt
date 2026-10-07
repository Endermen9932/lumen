package app.lumen.photos.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * The ".lumenindex" file written by the desktop app "Lumen Indexer" (Ubuntu): the image embeddings
 * of a photo folder that was indexed on a PC. A ZIP with three entries, in this order:
 *
 *  - `manifest.json` – format, model id, vector size, number of photos
 *  - `items.jsonl`   – one JSON line per photo: `n` file name, `p` folder, `s` size in bytes, `m` mtime
 *  - `vectors.bin`   – count × dim half floats (little endian, L2-normalised) – exactly the bytes the
 *    app keeps in its own index, line i of `items.jsonl` belongs to vector i
 *
 * Photos are matched to the phone's media by (file name, size), because both survive copying the
 * photos to the PC.
 */
object IndexImport {
    const val FORMAT = "lumen-index"
    const val SUPPORTED_VERSION = 1
    const val ENCODING = "fp16-le-l2"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Manifest(
        val format: String,
        val version: Int,
        val modelId: String,
        val modelName: String = "",
        val dim: Int,
        val count: Int,
        val encoding: String = ENCODING,
        val createdBy: String = "",
        val source: String = "",
    )

    @Serializable
    data class Item(
        /** File name. */
        val n: String,
        /** Folder relative to the folder that was indexed on the PC ("" = top level). */
        val p: String = "",
        /** Size in bytes. */
        val s: Long,
        /** Modification time (epoch seconds). */
        val m: Long = 0,
    )

    class Header(val manifest: Manifest, val items: List<Item>)

    /** The file is not a (complete) Lumen index. The message is shown to the user. */
    class FormatException(message: String) : Exception(message)

    /** Streams an index file; the entries must be read in file order (header first, then vectors). */
    class Reader(input: InputStream) : Closeable {
        private val zip = ZipInputStream(input.buffered(1 shl 16))

        fun readHeader(): Header {
            val manifestEntry = runCatching { zip.nextEntry }.getOrNull()
                ?: throw FormatException("Das ist keine Lumen-Indexierungsdatei.")
            if (manifestEntry.name != "manifest.json") throw FormatException("Das ist keine Lumen-Indexierungsdatei.")
            val manifest = try {
                json.decodeFromString<Manifest>(zip.readBytes().decodeToString())
            } catch (e: Exception) {
                throw FormatException("Die Indexierungsdatei ist beschädigt.")
            }
            if (manifest.format != FORMAT) throw FormatException("Das ist keine Lumen-Indexierungsdatei.")
            if (manifest.version > SUPPORTED_VERSION) {
                throw FormatException("Die Datei stammt von einer neueren Version des Lumen Indexers – bitte die Lumen-App aktualisieren.")
            }
            if (manifest.encoding != ENCODING || manifest.dim <= 0 || manifest.count < 0) {
                throw FormatException("Die Indexierungsdatei hat ein unbekanntes Format.")
            }

            if (zip.nextEntry?.name != "items.jsonl") throw FormatException("Die Indexierungsdatei ist beschädigt.")
            val items = ArrayList<Item>(manifest.count.coerceAtMost(1_000_000))
            val lines = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
            while (true) {
                val line = lines.readLine() ?: break
                if (line.isBlank()) continue
                items += try {
                    json.decodeFromString<Item>(line)
                } catch (e: Exception) {
                    throw FormatException("Die Indexierungsdatei ist beschädigt.")
                }
            }
            if (items.size != manifest.count) throw FormatException("Die Indexierungsdatei ist unvollständig.")

            if (zip.nextEntry?.name != "vectors.bin") throw FormatException("Die Indexierungsdatei ist beschädigt.")
            return Header(manifest, items)
        }

        /** Calls [onVector] with the raw fp16 bytes of every vector, in item order. The array is reused. */
        inline fun readVectors(header: Header, onVector: (index: Int, bytes: ByteArray) -> Unit) {
            val buffer = ByteArray(header.manifest.dim * 2)
            for (i in 0 until header.items.size) {
                readFully(buffer)
                onVector(i, buffer)
            }
        }

        fun readFully(buffer: ByteArray) {
            var read = 0
            while (read < buffer.size) {
                val n = zip.read(buffer, read, buffer.size - read)
                if (n < 0) throw FormatException("Die Indexierungsdatei ist abgeschnitten (Kopieren unterbrochen?).")
                read += n
            }
        }

        override fun close() = zip.close()
    }

    /** A photo or video of this phone. */
    data class LocalFile(
        val id: Long,
        val name: String,
        val size: Long,
        /** Album folder as MediaStore reports it, e.g. "DCIM/Camera/". */
        val folder: String,
        val dateModified: Long,
    )

    class MatchResult(
        /** Index of the item in the file -> phone photos that get its vector. */
        val byItem: Map<Int, List<LocalFile>>,
        /** Matched by name and size. */
        val exact: Int,
        /** Matched by name alone (the photo was changed since it was copied, e.g. compressed). */
        val byName: Int,
    ) {
        val matched: Int get() = exact + byName
    }

    private val DATE_STAMP = Regex("\\d{8}")

    /**
     * Only names with a date stamp (PXL_20240312_…, IMG-20240312-WA0001) are unique enough to be
     * matched without the size – "IMG_0001.jpg" from two cameras must not be mixed up.
     */
    fun isDistinctiveName(name: String) = DATE_STAMP.containsMatchIn(name)

    fun match(items: List<Item>, local: List<LocalFile>): MatchResult {
        val byKey = HashMap<Pair<String, Long>, MutableList<Int>>()
        val byName = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, item ->
            byKey.getOrPut(item.n to item.s) { ArrayList(1) }.add(i)
            byName.getOrPut(item.n) { ArrayList(1) }.add(i)
        }
        val localNames = local.groupingBy { it.name }.eachCount()

        val result = HashMap<Int, MutableList<LocalFile>>()
        var exact = 0
        var approx = 0
        for (file in local) {
            val candidates = byKey[file.name to file.size]
            val index: Int? = when {
                candidates != null -> {
                    exact++
                    candidates.maxByOrNull { folderScore(items[it].p, file.folder) }
                }
                else -> {
                    val sameName = byName[file.name]
                    if (sameName != null && sameName.size == 1 && localNames[file.name] == 1 && isDistinctiveName(file.name)) {
                        approx++
                        sameName[0]
                    } else null
                }
            }
            if (index != null) result.getOrPut(index) { ArrayList(1) }.add(file)
        }
        return MatchResult(result, exact, approx)
    }

    /** Number of equal trailing path segments ("DCIM/Camera/" vs "Camera" -> 1). */
    internal fun folderScore(exported: String, local: String): Int {
        val a = exported.trim('/').split('/').filter { it.isNotEmpty() }
        val b = local.trim('/').split('/').filter { it.isNotEmpty() }
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n].equals(b[b.size - 1 - n], ignoreCase = true)) n++
        return n
    }
}
