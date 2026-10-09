package app.lumen.photos.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Thumbnail request served from the MediaStore thumbnail cache. Much faster than decoding the
 * full-size JPEG for every grid cell and also works for videos.
 */
data class Thumb(val uri: Uri, val size: Int, val version: Long)

/**
 * Loads MediaStore thumbnails with two safety nets for fast scrolling through big galleries:
 * only a few decodes run at the same time (each one may make the media provider decode a whole
 * 50 MP original), and a request whose cell scrolled out of view is cancelled inside the media
 * provider instead of finishing in the background and piling up memory.
 */
object ThumbnailLoader {
    private val gate = Semaphore(4)

    suspend fun load(context: Context, uri: Uri, size: Int): Bitmap = gate.withPermit {
        val signal = CancellationSignal()
        coroutineScope {
            val job = async(Dispatchers.IO) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), signal)
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                signal.cancel()
                throw e
            } catch (e: OperationCanceledException) {
                throw CancellationException("Thumbnail cancelled").apply { initCause(e) }
            }
        }
    }
}

class ThumbFetcher(private val data: Thumb, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bitmap = ThumbnailLoader.load(context, data.uri, data.size)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(data, context)
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "thumb:${data.uri}:${data.size}:${data.version}"
}
