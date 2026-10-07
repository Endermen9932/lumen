package app.lumen.photos.work

import android.content.Context
import android.text.format.Formatter
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.ai.ModelCatalog
import app.lumen.photos.face.FaceModelCatalog
import app.lumen.photos.face.FaceModel
import app.lumen.photos.ai.AiModel

/** Downloads the weights of one model once. Afterwards everything runs offline. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        val modelId = inputData.getString(KEY_MODEL)
        val model: app.lumen.photos.ai.DownloadableModel = ModelCatalog.byId(modelId) ?: FaceModelCatalog.byId(modelId) ?: return Result.failure()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Lade " + when (model) { is AiModel -> model.name; is FaceModel -> "Gesichtserkennung ${model.tier}"; else -> "Modell" }
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_DOWNLOAD, title, "Verbinde …", 0, 0, cancel, dataSync = true))
        var lastUi = 0L
        return try {
            c.models.download(model) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUi > 700 || done >= total) {
                    lastUi = now
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    val permille = (done * 1000 / total.coerceAtLeast(1)).toInt()
                    safeForeground(
                        Notifications.progress(
                            applicationContext, Notifications.ID_DOWNLOAD, title,
                            "${Formatter.formatShortFileSize(applicationContext, done)} von ${Formatter.formatShortFileSize(applicationContext, total)}",
                            permille, 1000, cancel, dataSync = true
                        )
                    )
                }
            }
            when (model) {
                is AiModel -> {
                    if (c.settings.current.activeModelId == null || c.settings.current.activeModelId == model.id) {
                        c.ai.setActiveModel(model)
                    }
                    c.ai.scheduleIndexing()
                }
                is FaceModel -> {
                    if (c.settings.current.activeFaceModelId == null || c.settings.current.activeFaceModelId == model.id) {
                        c.faces.setActiveModel(model)
                    }
                    c.faces.schedule()
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (isStopped) Result.failure()
            else if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val TAG = "model-download"
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }
}
