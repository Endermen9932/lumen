package app.lumen.photos.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.ai.IndexImport
import app.lumen.photos.ai.ModelCatalog
import app.lumen.photos.data.db.EmbeddingEntity
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.data.db.FaceScanEntity
import app.lumen.photos.face.FaceModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a ".lumenindex" file (made by the desktop app) into the search index: every photo that
 * is on this phone and in the file gets its vector without being analysed again.
 */
class IndexImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return fail("Keine Datei ausgewählt.")
        return try {
            WorkLocks.hold(applicationContext, "index-import", false) { run(uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexImport.FormatException) {
            fail(e.message ?: "Die Datei konnte nicht gelesen werden.")
        } catch (e: Exception) {
            android.util.Log.w("IndexImportWorker", "Import failed", e)
            fail("Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    private suspend fun run(uri: Uri): Result = withContext(Dispatchers.IO) {
        val c = (applicationContext as LumenApp).container
        if (!BackgroundJobs.hasAnyMediaAccess(applicationContext)) return@withContext fail("Lumen hat noch keinen Zugriff auf die Fotos.")

        val stream = applicationContext.contentResolver.openInputStream(uri) ?: return@withContext fail("Die Datei konnte nicht geöffnet werden.")
        IndexImport.Reader(stream).use { reader ->
            val header = reader.readHeader()
            if (header.isFaces) return@withContext importFaces(reader, header)
            val manifest = header.manifest
            val model = ModelCatalog.byId(manifest.modelId)
                ?: return@withContext fail("Das Modell „${manifest.modelName.ifBlank { manifest.modelId }}“ kennt diese Version von Lumen nicht.")
            if (manifest.dim != model.embeddingDim) {
                return@withContext fail("Die Datei passt nicht zum Modell ${model.name} (Vektorgröße ${manifest.dim} statt ${model.embeddingDim}).")
            }

            val title = "Indexierung importieren"
            safeForeground(Notifications.progress(applicationContext, Notifications.ID_IMPORT, title, "Fotos werden zugeordnet …", 0, 0, dataSync = true))
            setProgress(progress(0, header.items.size, STAGE_MATCH))

            // The running indexer would compute the same vectors again – let it continue afterwards.
            val indexerWasActive = c.ai.activeModel.value == model
            if (indexerWasActive) c.ai.cancelIndexing()

            c.media.reload()
            val local = c.media.media.value
                .filter { it.isImage }
                .map { IndexImport.LocalFile(it.id, it.name, it.size, it.relativePath, it.dateModified) }
            val matches = IndexImport.match(header.items, local)

            val dao = c.db.embeddings()
            val alreadyIndexed = dao.indexedKeys(model.id).mapTo(HashSet()) { it.mediaId }

            var imported = 0
            var skipped = 0
            var lastUi = 0L
            val batch = ArrayList<EmbeddingEntity>(300)
            reader.readVectors(header) { i, bytes ->
                val targets = matches.byItem[i]
                if (targets != null) {
                    for (file in targets) {
                        if (file.id in alreadyIndexed) {
                            skipped++
                        } else {
                            batch += EmbeddingEntity(file.id, model.id, file.dateModified, bytes.copyOf())
                            imported++
                        }
                    }
                    if (batch.size >= 300) {
                        dao.upsert(batch.toList())
                        batch.clear()
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastUi > 500) {
                    lastUi = now
                    setProgress(progress(i + 1, header.items.size, STAGE_WRITE))
                    safeForeground(
                        Notifications.progress(
                            applicationContext, Notifications.ID_IMPORT, title,
                            "${i + 1} von ${header.items.size} Fotos gelesen", i + 1, header.items.size, dataSync = true
                        )
                    )
                }
            }
            if (batch.isNotEmpty()) dao.upsert(batch.toList())

            c.ai.onIndexImported(model)
            if (indexerWasActive) c.ai.scheduleIndexing()

            val unmatched = (local.size - matches.matched).coerceAtLeast(0)
            if (imported > 20) {
                Notifications.done(
                    applicationContext,
                    "Indexierung importiert",
                    "$imported Fotos haben jetzt einen KI-Suchindex (${model.name}) – ohne sie auf dem Handy zu analysieren."
                )
            }
            Result.success(
                workDataOf(
                    KEY_MODEL to model.id,
                    KEY_IMPORTED to imported,
                    KEY_SKIPPED to skipped,
                    KEY_BY_NAME to matches.byName,
                    KEY_IN_FILE to header.items.size,
                    KEY_ON_PHONE to local.size,
                    KEY_UNMATCHED to unmatched,
                )
            )
        }
    }

    /**
     * Faces found on the PC: every matched photo the phone has not scanned yet gets them (and counts
     * as scanned), then the usual grouping into persons runs. The PC uses the phone's recognition
     * network, so its faces and the ones the phone finds later end up in the same persons.
     */
    private suspend fun importFaces(reader: IndexImport.Reader, header: IndexImport.Header): Result {
        val c = (applicationContext as LumenApp).container
        val manifest = header.manifest
        val model = FaceModelCatalog.byId(manifest.modelId)
            ?: return fail("Das Gesichtsmodell „${manifest.modelName.ifBlank { manifest.modelId }}“ kennt diese Version von Lumen nicht.")
        val title = "Gesichter importieren"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_IMPORT, title, "Fotos werden zugeordnet …", 0, 0, dataSync = true))
        setProgress(progress(0, header.items.size, STAGE_MATCH))

        // The phone's own scan must not write the same photos at the same time.
        val scanWasActive = c.faces.activeModel.value == model
        if (scanWasActive) c.faces.cancel()

        c.media.reload()
        val local = c.media.media.value
            .filter { it.isImage }
            .map { IndexImport.LocalFile(it.id, it.name, it.size, it.relativePath, it.dateModified) }
        val matches = IndexImport.match(header.items, local)
        val dao = c.db.faces()
        val scanned = dao.scanKeys(model.id).mapTo(HashSet()) { it.mediaId }

        var photos = 0
        var faces = 0
        var skipped = 0
        var lastUi = 0L
        val faceBatch = ArrayList<FaceEntity>(256)
        val scanBatch = ArrayList<FaceScanEntity>(256)
        suspend fun flush() {
            if (faceBatch.isNotEmpty()) dao.insertFaces(faceBatch.toList())
            scanBatch.forEach { dao.upsertScan(it) }
            faceBatch.clear()
            scanBatch.clear()
        }
        reader.readFaceVectors(header) { i, vectors ->
            val item = header.items[i]
            for (file in matches.byItem[i].orEmpty()) {
                if (file.id in scanned) { skipped++; continue }
                scanned += file.id
                item.f.zip(vectors).forEach { (box, vector) ->
                    if (box.size < 5) return@forEach
                    faceBatch += FaceEntity(
                        mediaId = file.id, modelId = model.id,
                        left = box[0], top = box[1], right = box[2], bottom = box[3], score = box[4],
                        embedding = vector,
                    )
                }
                scanBatch += FaceScanEntity(file.id, model.id, file.dateModified, item.f.size)
                photos++
                faces += item.f.size
            }
            if (scanBatch.size >= 200) flush()
            val now = System.currentTimeMillis()
            if (now - lastUi > 500) {
                lastUi = now
                setProgress(progress(i + 1, header.items.size, STAGE_WRITE))
                safeForeground(
                    Notifications.progress(
                        applicationContext, Notifications.ID_IMPORT, title,
                        "${i + 1} von ${header.items.size} Fotos gelesen", i + 1, header.items.size, dataSync = true
                    )
                )
            }
        }
        flush()
        c.faces.assignUnassigned(model)
        if (scanWasActive) c.faces.schedule()
        if (photos > 20) {
            Notifications.done(applicationContext, "Gesichter importiert", "$faces Gesichter aus $photos Fotos – unter „Personen“ kannst du Namen vergeben.")
        }
        return Result.success(
            workDataOf(
                KEY_MODEL to model.id,
                KEY_IMPORTED to photos,
                KEY_SKIPPED to skipped,
                KEY_BY_NAME to matches.byName,
                KEY_IN_FILE to header.items.size,
                KEY_ON_PHONE to local.size,
                KEY_UNMATCHED to (local.size - matches.matched).coerceAtLeast(0),
                KEY_IS_FACES to true,
                KEY_FACES to faces,
            )
        )
    }

    private fun progress(done: Int, total: Int, stage: Int): Data = workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_STAGE to stage)

    companion object {
        const val NAME = "ai-index-import"
        const val KEY_URI = "uri"
        const val KEY_ERROR = "error"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STAGE = "stage"
        const val KEY_MODEL = "model"
        const val KEY_IMPORTED = "imported"
        const val KEY_SKIPPED = "skipped"
        const val KEY_BY_NAME = "byName"
        const val KEY_IN_FILE = "inFile"
        const val KEY_ON_PHONE = "onPhone"
        const val KEY_UNMATCHED = "unmatched"
        const val KEY_IS_FACES = "isFaces"
        const val KEY_FACES = "faces"
        const val STAGE_MATCH = 0
        const val STAGE_WRITE = 1
    }
}
