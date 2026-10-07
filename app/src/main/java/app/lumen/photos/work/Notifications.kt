package app.lumen.photos.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.lumen.photos.MainActivity
import app.lumen.photos.R

object Notifications {
    const val CHANNEL_WORK = "background_work"
    const val CHANNEL_DONE = "work_done"

    const val ID_INDEX = 1001
    const val ID_OPTIMIZE = 1002
    const val ID_DOWNLOAD = 1003
    const val ID_BACKUP = 1004
    const val ID_FACES = 1005
    const val ID_VIDEO = 1006
    const val ID_IMPORT = 1007
    const val ID_DONE = 1100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Hintergrundaufgaben", NotificationManager.IMPORTANCE_LOW).apply {
                description = "KI-Indexierung, Speicheroptimierung und Modell-Downloads"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Abgeschlossene Aufgaben", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun progress(
        context: Context,
        id: Int,
        title: String,
        text: String,
        done: Int,
        total: Int,
        cancelIntent: PendingIntent? = null,
        dataSync: Boolean = false,
        cancelLabel: String = "Abbrechen",
    ): ForegroundInfo {
        val builder = NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent(context))
            .setProgress(total.coerceAtLeast(0), done.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (cancelIntent != null) {
            builder.addAction(0, cancelLabel, cancelIntent)
        }
        val type = when {
            dataSync -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(id, builder.build(), type)
    }

    fun done(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            ID_DONE + (System.currentTimeMillis() % 100).toInt(),
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build()
        )
    }
}

/**
 * Promotes the worker to a foreground service when allowed. Android refuses this for jobs that
 * start while the app is in the background – the work then simply continues without it.
 */
suspend fun androidx.work.CoroutineWorker.safeForeground(info: ForegroundInfo) {
    runCatching { setForeground(info) }
}
