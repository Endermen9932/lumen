package app.lumen.photos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.sharedMedia
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Size in pixels used for grid thumbnails (and as placeholder key in the viewer). */
const val GRID_THUMB_SIZE = 384

/** Size used while the grid scrolls: cheap to decode, upgraded once scrolling stops. */
const val SCROLL_THUMB_SIZE = 128

fun thumbKey(item: MediaItem, size: Int = GRID_THUMB_SIZE) = "thumb:${item.uri}:$size:${item.dateModified}"

/** Thumbnail size for a grid with [columns] columns – smaller cells need fewer pixels. */
fun gridThumbSize(columns: Int): Int = when {
    columns <= 4 -> GRID_THUMB_SIZE
    columns <= 6 -> 256
    else -> 192
}

/** Every size a grid thumbnail can have, largest first (the viewer uses the best one cached). */
val GRID_THUMB_SIZES = listOf(GRID_THUMB_SIZE, 256, 192, SCROLL_THUMB_SIZE)

@Composable
fun MediaThumbnail(
    item: MediaItem,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    shared: Boolean = true,
    size: Int = GRID_THUMB_SIZE,
    cornerRadius: Int = 4,
    showBadges: Boolean = true,
    /** The grid is scrolling: load a small thumbnail unless the full one is already in memory. */
    lowRes: Boolean = false,
) {
    val context = LocalContext.current
    val useLow = lowRes && size > SCROLL_THUMB_SIZE &&
        SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(thumbKey(item, size))) == null
    val requestSize = if (useLow) SCROLL_THUMB_SIZE else size
    val request = remember(item.uri, item.dateModified, requestSize) {
        ImageRequest.Builder(context)
            .data(Thumb(item.uri, requestSize, item.dateModified))
            .memoryCacheKey(thumbKey(item, requestSize))
            // The sharp version replaces the small one without flashing the empty cell.
            .apply { if (requestSize != SCROLL_THUMB_SIZE) placeholderMemoryCacheKey(thumbKey(item, SCROLL_THUMB_SIZE)) }
            .crossfade(if (useLow) 0 else 120)
            .build()
    }
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    val corner by animateDpAsState(if (selected) 18.dp else cornerRadius.dp, label = "corner")
    val dim by animateFloatAsState(if (selectionMode && !selected) 0.9f else 1f, label = "dim")

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(RoundedCornerShape(corner))
                .graphicsLayer { alpha = dim }
                .sharedMedia(item.id, enabled = shared)
        )
        if (showBadges && (item.isVideo || item.isFavorite)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f), 0.35f to Color.Transparent
                        )
                    )
            )
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.isVideo) {
                    Text(
                        Format.duration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            if (item.isFavorite) {
                Icon(
                    Icons.Filled.Favorite, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(14.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = selectionMode,
            enter = scaleIn(),
            exit = scaleOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            } else {
                Icon(Icons.Outlined.Circle, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun pxToDp(px: Int) = with(LocalDensity.current) { px.toDp() }
