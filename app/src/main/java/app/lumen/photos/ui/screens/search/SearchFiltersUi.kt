package app.lumen.photos.ui.screens.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.data.location.LibraryPlace
import app.lumen.photos.face.Person
import app.lumen.photos.search.FilterSuggestion
import app.lumen.photos.search.MediaKind
import app.lumen.photos.search.PlaceLevel
import app.lumen.photos.search.SearchFilters
import app.lumen.photos.ui.components.FaceImage
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.components.crop
import app.lumen.photos.ui.screens.people.PortraitShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class FilterType(val label: String, val icon: ImageVector) {
    DATE("Datum", Icons.Outlined.CalendarMonth),
    KIND("Typ", Icons.Outlined.Category),
    PLACE("Ort", Icons.Outlined.Place),
    PERSON("Personen", Icons.Outlined.Face),
    ALBUM("Album", Icons.Outlined.PhotoLibrary),
    FAVORITES("Favoriten", Icons.Outlined.Favorite),
}

private fun SearchFilters.isActive(type: FilterType) = when (type) {
    FilterType.DATE -> hasDate
    FilterType.KIND -> kinds.isNotEmpty()
    FilterType.PLACE -> places.isNotEmpty()
    FilterType.PERSON -> persons.isNotEmpty()
    FilterType.ALBUM -> albums.isNotEmpty()
    FilterType.FAVORITES -> favoritesOnly
}

private fun SearchFilters.cleared(type: FilterType) = when (type) {
    FilterType.DATE -> copy(from = null, to = null)
    FilterType.KIND -> copy(kinds = emptySet())
    FilterType.PLACE -> copy(places = emptySet())
    FilterType.PERSON -> copy(persons = emptySet())
    FilterType.ALBUM -> copy(albums = emptySet())
    FilterType.FAVORITES -> copy(favoritesOnly = false)
}

private fun summary(names: List<String>, fallback: String): String = when (names.size) {
    0 -> fallback
    1 -> names[0]
    2 -> "${names[0]}, ${names[1]}"
    else -> "${names[0]} +${names.size - 1}"
}

/** The row of filter chips under the search field. Tap = edit, ✕ = remove the filter. */
@Composable
fun FilterBar(filters: SearchFilters, onFiltersChange: (SearchFilters) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val albums by c.media.albums.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FilterType?>(null) }
    // Active filters first, so what is set is always visible.
    val order = remember(filters) { FilterType.entries.sortedBy { if (filters.isActive(it)) 0 else 1 } }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(order, key = { it.name }) { type ->
            val active = filters.isActive(type)
            val label = when (type) {
                FilterType.DATE -> if (active) SearchFilters.dateLabel(filters.from, filters.to) else type.label
                FilterType.KIND -> summary(MediaKind.entries.filter { it in filters.kinds }.map { it.label }, type.label)
                FilterType.PLACE -> summary(filters.places.map { it.label }, type.label)
                FilterType.PERSON -> summary(filters.persons.mapNotNull { id -> persons.firstOrNull { it.id == id }?.displayName }, type.label)
                FilterType.ALBUM -> summary(filters.albums.mapNotNull { id -> albums.firstOrNull { it.id == id }?.name }, type.label)
                FilterType.FAVORITES -> type.label
            }
            FilterChip(
                selected = active,
                onClick = {
                    if (type == FilterType.FAVORITES) onFiltersChange(filters.copy(favoritesOnly = !filters.favoritesOnly))
                    else editing = type
                },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                leadingIcon = { Icon(type.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = if (active && type != FilterType.FAVORITES) {
                    {
                        Icon(
                            Icons.Filled.Close, "${type.label}-Filter entfernen",
                            Modifier.size(FilterChipDefaults.IconSize).clip(RoundedCornerShape(50)).clickable { onFiltersChange(filters.cleared(type)) }
                        )
                    }
                } else null,
                modifier = Modifier.animateItem(),
            )
        }
    }

    when (editing) {
        FilterType.DATE -> DateSheet(filters, onDone = { onFiltersChange(it); editing = null }, onDismiss = { editing = null })
        FilterType.KIND -> KindSheet(filters, onChange = onFiltersChange, onDismiss = { editing = null })
        FilterType.PLACE -> PlaceSheet(filters, onChange = onFiltersChange, onDismiss = { editing = null })
        FilterType.PERSON -> PersonSheet(filters, persons, onChange = onFiltersChange, onDismiss = { editing = null })
        FilterType.ALBUM -> AlbumSheet(filters, onChange = onFiltersChange, onDismiss = { editing = null })
        else -> Unit
    }
}

/** Filters suggested for the typed text – one tap sets the filter and removes the words. */
@Composable
fun SuggestionRow(
    suggestions: List<FilterSuggestion>,
    thinking: Boolean,
    onApply: (FilterSuggestion) -> Unit,
    onApplyAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "title") {
            Text("Als Filter:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(suggestions, key = { "${it.patch}" }) { s ->
            AssistChip(
                onClick = { onApply(s) },
                label = { Text(s.label, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        if (s.fromAi) Icons.Outlined.AutoAwesome else iconOf(s),
                        if (s.fromAi) "KI-Vorschlag" else null,
                        Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.animateItem(),
            )
        }
        if (suggestions.size >= 2) item(key = "all") {
            TextButton(onClick = onApplyAll) { Text("Alle übernehmen") }
        }
        if (thinking) item(key = "thinking") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp))
                Text(" KI denkt nach …", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun iconOf(s: FilterSuggestion): ImageVector = when (val p = s.patch) {
    is app.lumen.photos.search.FilterPatch.DateRange -> Icons.Outlined.CalendarMonth
    is app.lumen.photos.search.FilterPatch.Kind -> when (p.kind) {
        MediaKind.VIDEO -> Icons.Outlined.Videocam
        MediaKind.SCREENSHOT -> Icons.Outlined.Screenshot
        MediaKind.PHOTO -> Icons.Outlined.Photo
    }
    is app.lumen.photos.search.FilterPatch.Place -> Icons.Outlined.Place
    is app.lumen.photos.search.FilterPatch.Person -> Icons.Outlined.Face
    is app.lumen.photos.search.FilterPatch.Album -> Icons.Outlined.PhotoLibrary
    app.lumen.photos.search.FilterPatch.Favorites -> Icons.Outlined.Favorite
}

// ---------------------------------------------------------------------------------------- sheets

@Composable
private fun SheetTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun DateSheet(filters: SearchFilters, onDone: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filters.from?.toUtcMillis(),
        initialSelectedEndDateMillis = filters.to?.toUtcMillis(),
    )
    val presets = listOf(
        "Heute" to (today to today),
        "Letzte 7 Tage" to (today.minusDays(6) to today),
        "Letzte 30 Tage" to (today.minusDays(29) to today),
        "Dieses Jahr" to (LocalDate.of(today.year, 1, 1) to today),
        "Letztes Jahr" to (LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle("Zeitraum", "Nur Startdatum = ab diesem Tag")
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { (label, range) ->
                    AssistChip(onClick = { onDone(filters.copy(from = range.first, to = range.second)) }, label = { Text(label) })
                }
            }
            DateRangePicker(
                state = state,
                title = null,
                showModeToggle = true,
                modifier = Modifier.fillMaxWidth().height(440.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDone(filters.copy(from = null, to = null)) }) { Text("Zurücksetzen") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onDone(filters.copy(
                        from = state.selectedStartDateMillis?.toLocalDate(),
                        to = state.selectedEndDateMillis?.toLocalDate(),
                    ))
                }) { Text("Übernehmen") }
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String?, checked: Boolean, leading: (@Composable () -> Unit)? = null, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leading,
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@Composable
private fun KindSheet(filters: SearchFilters, onChange: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("Medientyp", "Mehrere Typen = einer davon")
            MediaKind.entries.forEach { kind ->
                val icon = when (kind) {
                    MediaKind.PHOTO -> Icons.Outlined.Photo
                    MediaKind.VIDEO -> Icons.Outlined.Videocam
                    MediaKind.SCREENSHOT -> Icons.Outlined.Screenshot
                }
                CheckRow(kind.label, null, kind in filters.kinds, leading = { Icon(icon, null) }) {
                    onChange(filters.copy(kinds = if (kind in filters.kinds) filters.kinds - kind else filters.kinds + kind))
                }
            }
        }
    }
}

@Composable
private fun PlaceSheet(filters: SearchFilters, onChange: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val places by c.locations.places.collectAsStateWithLifecycle()
    val scan by c.locations.scan.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(c.locations.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        if (granted) c.locations.refresh()
    }
    var search by remember { mutableStateOf("") }
    val shown = remember(places, search) {
        val q = search.trim().lowercase()
        places.filter { p -> q.isEmpty() || p.names.any { it.lowercase().contains(q) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle("Ort", "Aus den GPS-Daten deiner Fotos – mehrere Orte = einer davon")
            if (!permission) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text("Android gibt Lumen die Standorte der Fotos nur mit der Berechtigung „Standort in Medien“.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }, modifier = Modifier.padding(top = 8.dp)) { Text("Erlauben") }
                }
            }
            scan?.let { s ->
                Text(
                    "Orte werden ermittelt: ${Format.count(s.done)} von ${Format.count(s.total)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Ort, Region oder Land suchen") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                if (shown.isEmpty()) item {
                    Text(
                        if (places.isEmpty()) "Noch keine Fotos mit Standort gefunden." else "Kein passender Ort.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                for (level in listOf(PlaceLevel.COUNTRY, PlaceLevel.REGION, PlaceLevel.CITY)) {
                    val group = shown.filter { it.ref.level == level }
                    if (group.isEmpty()) continue
                    item(key = "h-$level") {
                        Text(
                            when (level) { PlaceLevel.COUNTRY -> "Länder"; PlaceLevel.REGION -> "Regionen"; PlaceLevel.CITY -> "Orte" },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    items(group.take(if (search.isBlank() && level == PlaceLevel.CITY) 300 else 1000), key = { "${it.ref.level}-${it.ref.key}" }) { p: LibraryPlace ->
                        val selected = filters.places.any { it.level == p.ref.level && it.key == p.ref.key }
                        CheckRow(p.ref.label, listOfNotNull(p.parent, "${Format.count(p.count)} Fotos").joinToString(" · "), selected) {
                            val without = filters.places.filterNot { it.level == p.ref.level && it.key == p.ref.key }.toSet()
                            onChange(filters.copy(places = if (selected) without else without + p.ref))
                        }
                    }
                }
                item {
                    Text(
                        "Ortsnamen: GeoNames (CC BY 4.0) · offline auf dem Gerät",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonSheet(filters: SearchFilters, persons: List<Person>, onChange: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val visible = remember(persons) { persons.filter { !it.hidden } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle("Personen")
            if (filters.persons.size >= 2) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                    SegmentedButton(
                        selected = filters.personsMatchAll,
                        onClick = { onChange(filters.copy(personsMatchAll = true)) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Alle zusammen") }
                    SegmentedButton(
                        selected = !filters.personsMatchAll,
                        onClick = { onChange(filters.copy(personsMatchAll = false)) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Mindestens eine") }
                }
            }
            if (visible.isEmpty()) {
                Text(
                    "Noch keine Personen – die Gesichtserkennung findest du im Tab „Personen“.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(visible, key = { it.id }) { p ->
                    val cover by produceState<FaceEntity?>(null, p.coverFace) { value = p.coverFace?.let { c.faces.face(it) } }
                    val item = remember(cover) { cover?.let { c.media.byId(it.mediaId) } }
                    val shape = PortraitShapes[(p.id % PortraitShapes.size).toInt()].toShape()
                    CheckRow(
                        p.displayName, "${Format.count(p.mediaIds.size)} Fotos", p.id in filters.persons,
                        leading = { FaceImage(if (cover != null && item != null) cover!!.crop(item.uri, 160) else null, shape, Modifier.size(44.dp)) },
                    ) {
                        onChange(filters.copy(persons = if (p.id in filters.persons) filters.persons - p.id else filters.persons + p.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumSheet(filters: SearchFilters, onChange: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val albums by c.media.albums.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val shown = remember(albums, search) { albums.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle("Album", "Mehrere Alben = eines davon")
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Album suchen") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(shown, key = { it.id }) { a ->
                    CheckRow(
                        a.name, "${Format.count(a.count)} Elemente", a.id in filters.albums,
                        leading = {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))) {
                                MediaThumbnail(a.cover, shared = false, showBadges = false, size = 128, modifier = Modifier.size(44.dp))
                            }
                        },
                    ) {
                        onChange(filters.copy(albums = if (a.id in filters.albums) filters.albums - a.id else filters.albums + a.id))
                    }
                }
            }
        }
    }
}

/** Small icon button that opens the filters when none is shown yet (kept for discoverability). */
@Composable
fun FilterHint(count: Int) {
    if (count == 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $count Filter aktiv", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
