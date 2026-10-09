package app.lumen.photos.ai

import app.lumen.photos.ai.IndexImport.Item
import app.lumen.photos.ai.IndexImport.LocalFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IndexImportTest {

    private fun sample() = javaClass.getResourceAsStream("/sample.lumenindex")!!

    @Test
    fun readsFileWrittenByTheDesktopApp() {
        IndexImport.Reader(sample()).use { reader ->
            val header = reader.readHeader()
            assertEquals("mobileclip-s0", header.manifest.modelId)
            assertEquals(4, header.manifest.dim)
            assertEquals(4, header.items.size)
            assertEquals(Item("PXL_20240312_120000.jpg", "Camera", 1111, 1710000000), header.items[1])
            // Non-ASCII names survive.
            assertEquals(Item("Foto ä.jpg", "Ümläut ünd Spaß", 4444, 1710000003), header.items[3])

            val vectors = ArrayList<FloatArray>()
            reader.readVectors(header) { _, bytes -> vectors += Fp16.decode(bytes) }
            assertEquals(4, vectors.size)
            assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), vectors[0], 0f)
            assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f), vectors[1], 0f)
            assertArrayEquals(floatArrayOf(0f, 0f, 0.6f, 0.8f), vectors[2], 1e-3f)
        }
    }

    @Test
    fun readsFacesWrittenByTheDesktopApp() {
        IndexImport.Reader(javaClass.getResourceAsStream("/sample_faces.lumenindex")!!).use { reader ->
            val header = reader.readHeader()
            assertTrue(header.isFaces)
            assertEquals("face-buffalo-l", header.manifest.modelId)
            assertEquals(2, header.manifest.faces)
            // Sorted by path: IMG_0001 (no faces) first.
            assertEquals(0, header.items[0].f.size)
            assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.9f), header.items[1].f[0])
            val faces = ArrayList<FloatArray>()
            reader.readFaceVectors(header) { _, vectors -> vectors.forEach { faces += Fp16.decode(it) } }
            assertEquals(2, faces.size)
            assertArrayEquals(floatArrayOf(0f, 0.6f, 0.8f, 0f), faces[1], 1e-3f)
        }
    }

    @Test
    fun rejectsOtherZipFiles() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("hello.txt")); z.write("hi".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        try {
            IndexImport.Reader(ByteArrayInputStream(bytes)).readHeader()
            fail("expected FormatException")
        } catch (e: IndexImport.FormatException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun rejectsTruncatedVectors() {
        val full = sample().readBytes()
        // Cut inside the central directory / vectors: reading all vectors must fail cleanly.
        val cut = full.copyOf(full.size - 200)
        try {
            IndexImport.Reader(ByteArrayInputStream(cut)).use { reader ->
                val header = reader.readHeader()
                reader.readVectors(header) { _, _ -> }
            }
            fail("expected an exception")
        } catch (e: IndexImport.FormatException) {
            assertNotNull(e.message)
        } catch (e: java.io.IOException) {
            // ZipInputStream reports the cut as an I/O problem – the worker shows a generic error.
        }
    }

    private fun local(id: Long, name: String, size: Long, folder: String = "DCIM/Camera/") = LocalFile(id, name, size, folder, 1000 + id)

    @Test
    fun matchesByNameAndSize() {
        val items = listOf(Item("PXL_20240312_120000.jpg", "Camera", 1111), Item("IMG_0001.jpg", "Camera", 2222))
        val result = IndexImport.match(items, listOf(local(1, "IMG_0001.jpg", 2222), local(2, "PXL_20240312_120000.jpg", 1111), local(3, "other.jpg", 5)))
        assertEquals(2, result.exact)
        assertEquals(0, result.byName)
        assertEquals(listOf(2L), result.byItem[0]!!.map { it.id })
        assertEquals(listOf(1L), result.byItem[1]!!.map { it.id })
    }

    @Test
    fun identicalCopiesOnThePhoneAllGetTheVector() {
        val items = listOf(Item("PXL_20240312_120000.jpg", "Camera", 1111))
        val result = IndexImport.match(items, listOf(local(1, "PXL_20240312_120000.jpg", 1111), local(2, "PXL_20240312_120000.jpg", 1111, "Pictures/Shared/")))
        assertEquals(setOf(1L, 2L), result.byItem[0]!!.map { it.id }.toSet())
    }

    @Test
    fun sameNameAndSizeInTwoFoldersPicksTheMatchingFolder() {
        val items = listOf(Item("IMG_0001.jpg", "Urlaub", 100), Item("IMG_0001.jpg", "Camera", 100))
        val result = IndexImport.match(items, listOf(local(1, "IMG_0001.jpg", 100, "DCIM/Camera/"), local(2, "IMG_0001.jpg", 100, "Pictures/Urlaub/")))
        assertEquals(listOf(1L), result.byItem[1]!!.map { it.id })
        assertEquals(listOf(2L), result.byItem[0]!!.map { it.id })
    }

    @Test
    fun changedSizeIsMatchedByNameOnlyForDatedFileNames() {
        val items = listOf(Item("PXL_20240312_120000.jpg", "Camera", 5_000_000), Item("IMG_0001.jpg", "Camera", 3_000_000))
        // The phone compressed both photos after they were copied.
        val result = IndexImport.match(items, listOf(local(1, "PXL_20240312_120000.jpg", 900_000), local(2, "IMG_0001.jpg", 500_000)))
        assertEquals(0, result.exact)
        assertEquals(1, result.byName)
        assertEquals(listOf(1L), result.byItem[0]!!.map { it.id })
        assertFalse(result.byItem.containsKey(1)) // "IMG_0001.jpg" is too generic to trust without the size
    }

    @Test
    fun folderScoreCountsTrailingSegments() {
        assertEquals(2, IndexImport.folderScore("DCIM/Camera", "DCIM/Camera/"))
        assertEquals(1, IndexImport.folderScore("Camera", "DCIM/Camera/"))
        assertEquals(0, IndexImport.folderScore("", "DCIM/Camera/"))
        assertEquals(0, IndexImport.folderScore("Urlaub", "DCIM/Camera/"))
        assertTrue(IndexImport.isDistinctiveName("PXL_20240312_120000.jpg"))
        assertFalse(IndexImport.isDistinctiveName("IMG_0001.jpg"))
    }
}
