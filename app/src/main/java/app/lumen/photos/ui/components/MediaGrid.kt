package app.lumen.photos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lumen.photos.data.media.MediaItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Grouping { AUTO, DAY, MONTH, NONE }

sealed interface GridEntry {
    val key: Any

    data class Header(override val key: String, val title: String, val subtitle: String?, val ids: List<Long>) : GridEntry
    data class Media(val item: MediaItem, val index: Int) : GridEntry {
        override val key: Any get() = item.id
    }
}

fun buildEntries(items: List<MediaItem>, columns: Int, grouping: Grouping): List<GridEntry> {
    val mode = when (grouping) {
        Grouping.AUTO -> when {
            columns <= 4 -> Grouping.DAY
            else -> Grouping.MONTH
        }
        else -> grouping
    }
    if (mode == Grouping.NONE) return items.mapIndexed { i, it -> GridEntry.Media(it, i) }
    val result = ArrayList<GridEntry>(items.size + items.size / 8)
    var currentKey: String? = null
    var groupStart = 0
    var headerIndex = -1
    fun closeGroup(end: Int) {
        if (headerIndex >= 0) {
            val h = result[headerIndex] as GridEntry.Header
            val ids = items.subList(groupStart, end).map { it.id }
            result[headerIndex] = h.copy(ids = ids, subtitle = if (mode == Grouping.MONTH) "${ids.size} Elemente" else null)
        }
    }
    items.forEachIndexed { i, item ->
        val date = Format.localDate(item.timestamp)
        val key = if (mode == Grouping.DAY) date.toString() else "${date.year}-${date.monthValue}"
        if (key != currentKey) {
            closeGroup(i)
            currentKey = key
            groupStart = i
            val title = if (mode == Grouping.DAY) Format.dayHeader(item.timestamp) else Format.monthHeader(item.timestamp)
            result += GridEntry.Header("h-$key", title, null, emptyList())
            headerIndex = result.lastIndex
        }
        result += GridEntry.Media(item, i)
    }
    closeGroup(items.size)
    return result
}

/**
 * Photo grid with date headers, pinch-to-zoom column switching, long-press + drag multi
 * selection with auto scrolling, a fast scroller and shared element transitions.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    grouping: Grouping = Grouping.AUTO,
    minColumns: Int = 2,
    maxColumns: Int = 8,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val entries = remember(items, columns, grouping) { buildEntries(items, columns, grouping) }
    val headerOffset = if (header != null) 1 else 0
    val selectionMode = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelection by rememberUpdatedState(onSelectionChange)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentColumns by rememberUpdatedState(columns)
    val currentOnColumns by rememberUpdatedState(onColumnsChange)
    val currentEntries by rememberUpdatedState(entries)
    val currentItems by rememberUpdatedState(items)

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var dragUpdate by remember { mutableStateOf<((Offset) -> Unit)?>(null) }

    fun entryAt(position: Offset): GridEntry? {
        // Item offsets are relative to the content start (after the top content padding), while the
        // pointer position is relative to the grid's bounds – convert before hit testing.
        val layout = state.layoutInfo
        val y = position.y + layout.viewportStartOffset
        val x = position.x
        val info = layout.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x < it.offset.x + it.size.width &&
                y >= it.offset.y && y < it.offset.y + it.size.height
        } ?: return null
        return currentEntries.getOrNull(info.index - headerOffset)
    }

    LaunchedEffect(state) {
        while (true) {
            withFrameNanos { }
            if (autoScrollSpeed != 0f) {
                state.scrollBy(autoScrollSpeed)
                dragPointer?.let { p -> dragUpdate?.invoke(p) }
            }
        }
    }

    val edge = with(LocalDensity.current) { 96.dp.toPx() }
    // While the grid moves, cells load small thumbnails; the sharp ones follow once it stops.
    var fastScrolling by remember { mutableStateOf(false) }
    val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
    val lowRes = scrolling || fastScrolling
    val thumbSize = gridThumbSize(columns)

    BoxWithConstraints(modifier) {
        val viewportHeight = constraints.maxHeight.toFloat()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // Pinch to change the number of columns.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoom *= event.calculateZoom()
                                val c = currentColumns
                                if (zoom > 1.3f && c > minColumns) {
                                    currentOnColumns(c - 1); zoom = 1f
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                } else if (zoom < 0.77f && c < maxColumns) {
                                    currentOnColumns(c + 1); zoom = 1f
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Tap to open / toggle, long press + drag to select a range.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var tap: Offset? = null
                        var cancelled = false
                        val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || event.changes.size > 1 || change.isConsumed) {
                                    cancelled = true; break
                                }
                                if (!change.pressed) {
                                    tap = change.position; break
                                }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    cancelled = true; break
                                }
                            }
                        }
                        if (finished != null) {
                            if (!cancelled) tap?.let { p ->
                                when (val e = entryAt(p)) {
                                    is GridEntry.Media -> {
                                        if (currentSelection.isNotEmpty()) {
                                            val sel = currentSelection
                                            currentOnSelection(if (e.item.id in sel) sel - e.item.id else sel + e.item.id)
                                        } else {
                                            currentOnOpen(e.item)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Long press.
                        val start = entryAt(down.position) as? GridEntry.Media ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val base = currentSelection
                        val deselect = start.item.id in base && base.isNotEmpty()
                        var lastIndex = -1
                        val update: (Offset) -> Unit = { p ->
                            val e = entryAt(p) as? GridEntry.Media
                            if (e != null && e.index != lastIndex) {
                                lastIndex = e.index
                                val lo = minOf(start.index, e.index)
                                val hi = maxOf(start.index, e.index)
                                val range = currentItems.subList(lo, hi + 1).mapTo(HashSet()) { it.id }
                                currentOnSelection(if (deselect) base - range else base + range)
                            }
                        }
                        update(down.position)
                        dragUpdate = update
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            dragPointer = change.position
                            update(change.position)
                            val y = change.position.y
                            autoScrollSpeed = when {
                                y < edge -> -((edge - y) / edge) * 28f
                                y > viewportHeight - edge -> ((y - (viewportHeight - edge)) / edge) * 28f
                                else -> 0f
                            }
                        }
                        autoScrollSpeed = 0f
                        dragPointer = null
                        dragUpdate = null
                    }
                },
        ) {
            if (header != null) {
                item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header-slot") { header() }
            }
            for (entry in entries) {
                when (entry) {
                    is GridEntry.Header -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        DateHeader(
                            entry = entry,
                            selectionMode = selectionMode,
                            allSelected = selectionMode && entry.ids.all { it in selection },
                            onToggle = {
                                val all = entry.ids.all { it in selection }
                                onSelectionChange(if (all) selection - entry.ids.toSet() else selection + entry.ids)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    is GridEntry.Media -> item(key = entry.key, contentType = "media") {
                        MediaThumbnail(
                            item = entry.item,
                            selectionMode = selectionMode,
                            selected = entry.item.id in selection,
                            cornerRadius = if (columns >= 6) 2 else 4,
                            showBadges = columns <= 6,
                            size = thumbSize,
                            lowRes = lowRes,
                            modifier = Modifier
                                .animateItem()
                                .aspectRatio(1f)
                                .padding(1.dp),
                        )
                    }
                }
            }
            if (footer != null) {
                item(key = "grid-footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") { footer() }
            }
        }

        FastScroller(
            state = state,
            entries = entries,
            headerOffset = headerOffset,
            topPadding = contentPadding.calculateTopPadding(),
            bottomPadding = contentPadding.calculateBottomPadding(),
            modifier = Modifier.align(Alignment.TopEnd),
            onScrollTo = { index -> scope.launch { state.scrollToItem(index) } },
            onDraggingChange = { fastScrolling = it },
        )
    }
}

@Composable
private fun DateHeader(
    entry: GridEntry.Header,
    selectionMode: Boolean,
    allSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = selectionMode, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = "Gruppe auswählen",
                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FastScroller(
    state: LazyGridState,
    entries: List<GridEntry>,
    headerOffset: Int,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onScrollTo: (Int) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.size < 60) return
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { onDraggingChange(dragging) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
    val visible = dragging || scrolling
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scroller")
    val fraction by remember(entries) {
        derivedStateOf {
            val total = state.layoutInfo.totalItemsCount
            val visibleCount = state.layoutInfo.visibleItemsInfo.size
            if (total <= visibleCount) 0f
            else (state.firstVisibleItemIndex.toFloat() / (total - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    val shown = if (dragging) dragFraction else fraction
    val label = remember(shown, entries) {
        val idx = ((entries.size - 1) * shown).roundToInt().coerceIn(0, entries.lastIndex)
        when (val e = entries.getOrNull(idx)) {
            is GridEntry.Media -> Format.monthHeader(e.item.timestamp)
            is GridEntry.Header -> entries.drop(idx + 1).firstNotNullOfOrNull { it as? GridEntry.Media }
                ?.let { Format.monthHeader(it.item.timestamp) } ?: e.title
            null -> ""
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp)
            .width(160.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        val thumbHeight = 52.dp
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(LocalDensity.current) { thumbHeight.toPx() }
        val y = ((trackPx - thumbPx) * shown).roundToInt()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(36.dp)
                .pointerInput(entries) {
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            dragFraction = ((o.y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
                            onScrollTo(((entries.size - 1) * dragFraction).roundToInt() + headerOffset)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        if (alpha < 0.5f && !dragging) return@detectVerticalDragGestures
                        dragFraction = (dragFraction + delta / (trackPx - thumbPx)).coerceIn(0f, 1f)
                        onScrollTo(((entries.size - 1) * dragFraction).roundToInt() + headerOffset)
                    }
                }
        )
        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 6.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .size(width = 22.dp, height = thumbHeight)
        ) {}
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-40.dp.roundToPx(), y + 6.dp.roundToPx()) }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).height(20.dp)
                )
            }
        }
    }
}

@Composable
fun GridSpacer(height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(height).background(androidx.compose.ui.graphics.Color.Transparent))
}
