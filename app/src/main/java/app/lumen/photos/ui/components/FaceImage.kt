package app.lumen.photos.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.lumen.photos.data.db.FaceEntity
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import kotlin.math.max
import kotlin.math.roundToInt

/** A square crop around one detected face (with some margin), served through Coil. */
data class FaceCrop(val uri: Uri, val faceId: Long, val left: Float, val top: Float, val right: Float, val bottom: Float, val size: Int)

fun FaceEntity.crop(uri: Uri, size: Int = 320) = FaceCrop(uri, id, left, top, right, bottom, size)

class FaceCropFetcher(private val data: FaceCrop, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Only as many pixels as the crop needs: a big face needs a small thumbnail. Loading the
        // full 1280 px version for every portrait made long person lists run out of memory.
        val faceFraction = max(data.right - data.left, data.bottom - data.top).coerceAtLeast(0.01f) * 1.7f
        val sourceSize = (data.size / faceFraction).roundToInt().coerceIn(256, 1280)
        val src = ThumbnailLoader.load(context, data.uri, sourceSize)
        val w = src.width
        val h = src.height
        val cx = (data.left + data.right) / 2f * w
        val cy = (data.top + data.bottom) / 2f * h
        val side = max((data.right - data.left) * w, (data.bottom - data.top) * h) * 1.7f
        val half = side / 2f
        val rect = Rect(
            (cx - half).roundToInt().coerceAtLeast(0),
            (cy - half).roundToInt().coerceAtLeast(0),
            (cx + half).roundToInt().coerceAtMost(w),
            (cy + half).roundToInt().coerceAtMost(h),
        )
        val out = Bitmap.createBitmap(data.size, data.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        // Keep the face centred and square even at image borders.
        val scale = data.size / side
        val dst = android.graphics.RectF(
            (rect.left - (cx - half)) * scale,
            (rect.top - (cy - half)) * scale,
            (rect.right - (cx - half)) * scale,
            (rect.bottom - (cy - half)) * scale,
        )
        canvas.drawBitmap(soft, rect, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (soft !== src) soft.recycle()
        src.recycle()
        return ImageFetchResult(image = out.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<FaceCrop> {
        override fun create(data: FaceCrop, options: Options, imageLoader: ImageLoader): Fetcher = FaceCropFetcher(data, context)
    }
}

class FaceCropKeyer : Keyer<FaceCrop> {
    override fun key(data: FaceCrop, options: Options) = "face:${data.faceId}:${data.size}:${data.left}:${data.top}"
}

@Composable
fun FaceImage(crop: FaceCrop?, shape: Shape, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (crop == null) {
            Icon(Icons.Outlined.Face, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val request = remember(crop) { ImageRequest.Builder(context).data(crop).crossfade(150).build() }
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}
