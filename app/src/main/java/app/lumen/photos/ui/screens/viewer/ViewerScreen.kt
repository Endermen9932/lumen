package app.lumen.photos.ui.screens.viewer

import android.app.Activity
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import app.lumen.photos.ui.LocalAppSettings
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.thumbKey
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.rememberMediaActions
import app.lumen.photos.ui.sharedMedia
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

@Composable
fun ViewerScreen(source: String, startId: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    val flow = remember(source) { c.lists.source(source, c.media, c.settings) }
    val items by flow.collectAsStateWithLifecycle(initialValue = null)
    val list = items
    val isTrash = source == MediaListRegistry.SOURCE_TRASH

    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<MediaItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dismissFraction by remember { mutableStateOf(0f) }

    // Predictive back: the photo shrinks and follows the finger, the black backdrop fades. On
    // release the viewer closes from exactly there (without the shared-element flight, which
    // would first swap the photo for the cropped grid thumbnail).
    val backAnimations = LocalAppSettings.current.backGestureAnimations
    val backProgress = remember { Animatable(0f) }
    val backOffsetY = remember { Animatable(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    var closingByGesture by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = backAnimations && info == null && !closingByGesture) { events ->
        var startY = Float.NaN
        try {
            events.collect { e ->
                if (startY.isNaN()) startY = e.touchY
                backFromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress.snapTo(e.progress)
                backOffsetY.snapTo(e.touchY - startY)
            }
            closingByGesture = true
            nav.back()
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f, spring(dampingRatio = 0.8f)) }
            scope.launch { backOffsetY.animateTo(0f, spring(dampingRatio = 0.8f)) }
            throw e
        }
    }
    val backFraction = backProgress.value

    ImmersiveMode(!chrome)

    if (list == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { LoadingIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val startIndex = remember { list.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { list.size }
    var currentId by remember { mutableStateOf(startId) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { p -> list.getOrNull(p)?.let { currentId = it.id } } }
    // Keep the same photo in view if the list changes (e.g. after deleting).
    LaunchedEffect(list) {
        val idx = list.indexOfFirst { it.id == currentId }
        if (idx >= 0 && idx != pager.currentPage) pager.scrollToPage(idx)
    }
    val current = list.getOrNull(pager.currentPage.coerceIn(0, list.lastIndex))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - dismissFraction * 1.6f - backFraction * 0.55f).coerceIn(0f, 1f)))
    ) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { list.getOrNull(it)?.id ?: it },
            userScrollEnabled = backFraction == 0f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = backProgress.value
                    val scale = 1f - 0.22f * p
                    scaleX = scale
                    scaleY = scale
                    translationX = (if (backFromLeft) 1f else -1f) * p * 28.dp.toPx()
                    translationY = backOffsetY.value * 0.45f
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0f
                }
        ) { page ->
            val item = list[page]
            val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.12f, rotateOnDrag = false)
            val isCurrent = page == pager.currentPage
            if (isCurrent) {
                LaunchedEffect(flick) {
                    snapshotFlow { flick.offsetFraction }.collect { dismissFraction = kotlin.math.abs(it) }
                }
                LaunchedEffect(flick) {
                    snapshotFlow { flick.gestureState }.collect { if (it is FlickToDismissState.GestureState.Dismissing) nav.back() }
                }
            }
            FlickToDismiss(state = flick) {
                if (item.isVideo) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = null, indication = null) { chrome = !chrome }
                    ) {
                        VideoPlayer(
                            uri = item.uri,
                            aspectRatio = item.aspectRatio,
                            active = isCurrent,
                            showControls = chrome,
                            modifier = Modifier.sharedMedia(item.id, enabled = isCurrent && !closingByGesture),
                        )
                    }
                } else {
                    val zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 12f)))
                    val request = remember(item.uri, item.dateModified) {
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .placeholderMemoryCacheKey(MemoryCache.Key(thumbKey(item)))
                            .build()
                    }
                    ZoomableAsyncImage(
                        model = request,
                        contentDescription = item.name,
                        state = zoom,
                        onClick = { chrome = !chrome },
                        modifier = Modifier.fillMaxSize().sharedMedia(item.id, enabled = isCurrent && !closingByGesture),
                    )
                }
            }
        }

        // Top bar.
        AnimatedVisibility(
            visible = chrome && dismissFraction < 0.02f && backFraction == 0f,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
                if (current != null) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(Format.dayHeader(current.timestamp), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${Format.full(current.timestamp).substringAfter("· ")} · ${pager.currentPage + 1}/${list.size}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mehr", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (current != null && current.isImage) {
                            DropdownMenuItem(
                                text = { Text("Ähnliche Fotos (KI)") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val result = c.ai.similar(current)
                                        if (result == null || result.items.isEmpty()) {
                                            Toast.makeText(context, "Dieses Foto ist noch nicht KI-indexiert", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val key = c.lists.register("similar:${current.id}", result.items)
                                            nav.collection(key, "Ähnliche Fotos")
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Als Hintergrund") },
                                leadingIcon = { Icon(Icons.Outlined.Wallpaper, null) },
                                onClick = { menu = false; scope.launch { actions.setWallpaper(current) } }
                            )
                        }
                        if (current != null) {
                            DropdownMenuItem(
                                text = { Text("Verwenden als …") },
                                leadingIcon = { Icon(Icons.Outlined.Wallpaper, null) },
                                onClick = { menu = false; actions.useAs(current) }
                            )
                            DropdownMenuItem(
                                text = { Text("Öffnen mit …") },
                                leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                                onClick = { menu = false; actions.openWith(current) }
                            )
                        }
                    }
                }
            }
        }

        // Bottom floating toolbar.
        AnimatedVisibility(
            visible = chrome && dismissFraction < 0.02f && backFraction == 0f && current != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            val item = current ?: return@AnimatedVisibility
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            ) {
                if (isTrash) {
                    IconButton(onClick = { scope.launch { actions.restore(listOf(item)) } }) {
                        Icon(Icons.Outlined.RestoreFromTrash, "Wiederherstellen")
                    }
                    IconButton(onClick = { scope.launch { actions.deleteForever(listOf(item)) } }) {
                        Icon(Icons.Outlined.Delete, "Endgültig löschen")
                    }
                } else {
                    IconButton(onClick = { actions.share(listOf(item)) }) { Icon(Icons.Outlined.Share, "Teilen") }
                    IconButton(onClick = { scope.launch { actions.setFavorite(listOf(item), !item.isFavorite) } }) {
                        Icon(
                            if (item.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            "Favorit",
                        )
                    }
                    if (item.isImage) {
                        IconButton(onClick = { nav.editor(item.id) }) { Icon(Icons.Outlined.Edit, "Bearbeiten") }
                    }
                    IconButton(onClick = { info = item }) { Icon(Icons.Outlined.Info, "Info") }
                    IconButton(onClick = { scope.launch { actions.trash(listOf(item)) } }) { Icon(Icons.Outlined.Delete, "Löschen") }
                }
            }
        }
    }

    info?.let { InfoSheet(it, onDismiss = { info = null }) }
}

@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = false
        }
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun ExternalViewerScreen(uri: String, mime: String?) {
    val nav = LocalNavigator.current
    val parsed = remember(uri) { android.net.Uri.parse(uri) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mime?.startsWith("video/") == true) {
            VideoPlayer(parsed, 16f / 9f, active = true, showControls = true)
        } else {
            ZoomableAsyncImage(
                model = parsed,
                contentDescription = null,
                state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 12f))),
                modifier = Modifier.fillMaxSize().graphicsLayer { }
            )
        }
        IconButton(onClick = { nav.back() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White)
        }
    }
}
