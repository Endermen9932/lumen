package app.lumen.photos.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lumen.photos.AppContainer
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.ai.DateQueryParser
import app.lumen.photos.ai.SearchResult
import app.lumen.photos.container
import app.lumen.photos.work.BackgroundJobs
import app.lumen.photos.data.media.Album
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.Grouping
import app.lumen.photos.ui.components.MediaGrid
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.components.SelectionBar
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Concept(val label: String, val prompt: String)

private val conceptsDe = listOf(
    Concept("Strand", "ein Strand am Meer"), Concept("Berge", "Berge und Landschaft"),
    Concept("Sonnenuntergang", "ein Sonnenuntergang"), Concept("Essen", "ein Teller mit Essen"),
    Concept("Hunde", "ein Hund"), Concept("Katzen", "eine Katze"),
    Concept("Selfies", "ein Selfie"), Concept("Schnee", "Schnee im Winter"),
    Concept("Autos", "ein Auto"), Concept("Blumen", "Blumen"),
    Concept("Städte", "eine Stadt mit Gebäuden"), Concept("Dokumente", "ein Dokument mit Text"),
    Concept("Nacht", "ein Foto in der Nacht"), Concept("Wald", "ein Wald mit Bäumen"),
    Concept("Party", "eine Party mit Freunden"), Concept("Kinder", "spielende Kinder"),
)
private val conceptsEn = listOf(
    Concept("Strand", "a beach by the sea"), Concept("Berge", "mountains landscape"),
    Concept("Sonnenuntergang", "a sunset"), Concept("Essen", "a plate of food"),
    Concept("Hunde", "a dog"), Concept("Katzen", "a cat"),
    Concept("Selfies", "a selfie"), Concept("Schnee", "snow in winter"),
    Concept("Autos", "a car"), Concept("Blumen", "flowers"),
    Concept("Städte", "a city with buildings"), Concept("Dokumente", "a document with text"),
    Concept("Nacht", "a photo at night"), Concept("Wald", "a forest with trees"),
    Concept("Party", "a party with friends"), Concept("Kinder", "children playing"),
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _result = MutableStateFlow<SearchResult?>(null)
    val result = _result.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums = _albums.asStateFlow()
    private val _explore = MutableStateFlow<List<Pair<Concept, MediaItem>>>(emptyList())
    val explore = _explore.asStateFlow()
    /** The date the query was understood as (e.g. "Juli 2023"), shown above the results. */
    private val _dateLabel = MutableStateFlow<String?>(null)
    val dateLabel = _dateLabel.asStateFlow()
    private var job: Job? = null
    private var exploreModel: String? = null
    private var exploreSize = -1

    init {
        c.ai.warmUp()
    }

    @OptIn(FlowPreview::class)
    fun setQuery(q: String, immediate: Boolean = false) {
        _query.value = q
        job?.cancel()
        if (q.isBlank()) {
            _result.value = null
            _albums.value = emptyList()
            _dateLabel.value = null
            _loading.value = false
            return
        }
        job = viewModelScope.launch {
            if (!immediate) delay(380)
            _loading.value = true
            val lower = q.trim().lowercase()
            _albums.value = c.media.albums.value.filter { it.name.lowercase().contains(lower) }
            val (people, afterPeople) = c.faces.matchQuery(q)
            val date = DateQueryParser.parse(afterPeople)
            _dateLabel.value = date.filter?.label
            _result.value = runCatching {
                // Named persons and a date narrow the search down; the remaining words rank the rest.
                // "Paul Anna" = photos with both, "Strand Juli 2023" = beach photos from July 2023.
                var allowed: Set<Long>? = people.takeIf { it.isNotEmpty() }
                    ?.map { it.mediaIds.toSet() }?.reduce { a, b -> a intersect b }
                date.filter?.let { filter ->
                    val onDate = withContext(Dispatchers.Default) {
                        c.media.media.value.filter { filter.matches(Format.localDate(it.timestamp)) }.mapTo(HashSet()) { it.id }
                    }
                    allowed = allowed?.let { it intersect onDate } ?: onDate
                }
                val ids = allowed
                if (ids != null) c.ai.searchWithin(date.rest, ids, q) else c.ai.search(q)
            }.getOrNull()
            _loading.value = false
        }
    }

    fun loadExplore() {
        val model = c.ai.activeModel.value ?: return
        val size = c.index.size.value
        if (size < 20 || (exploreModel == model.id && kotlin.math.abs(size - exploreSize) < 50)) return
        exploreModel = model.id
        exploreSize = size
        viewModelScope.launch {
            val concepts = if (model.multilingual) conceptsDe else conceptsEn
            val out = ArrayList<Pair<Concept, MediaItem>>()
            val used = HashSet<Long>()
            for (concept in concepts) {
                val hit = runCatching { c.ai.bestMatch(concept.prompt) }.getOrNull() ?: continue
                if (used.add(hit.id)) {
                    out += concept to hit
                    _explore.value = out.toList()
                }
            }
        }
    }
}

@Composable
fun SearchTab(onSelectionModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val vm: SearchViewModel = viewModel { SearchViewModel(context.container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val explore by vm.explore.collectAsStateWithLifecycle()
    val dateLabel by vm.dateLabel.collectAsStateWithLifecycle()
    val model by c.ai.activeModel.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val indexed by c.ai.indexedCount.collectAsStateWithLifecycle()
    val indexSize by c.index.size.collectAsStateWithLifecycle()
    val progress by c.ai.indexProgress.collectAsStateWithLifecycle(initialValue = null)
    val media by c.media.media.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var columns by remember { mutableStateOf(3) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val namedPersons = remember(persons) { persons.filter { it.name != null && !it.hidden } }
    val ready = (model != null && model!!.id in installed) || namedPersons.isNotEmpty()

    LaunchedEffect(indexSize, ready) { if (ready) vm.loadExplore() }
    LaunchedEffect(selection.isNotEmpty()) { onSelectionModeChange(selection.isNotEmpty()) }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = query.isNotEmpty() && selection.isEmpty()) { vm.setQuery("") }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AnimatedContent(selection.isNotEmpty(), label = "searchbar") { selecting ->
            val items = result?.items.orEmpty()
            if (selecting) {
                SelectionBar(
                    selected = items.filter { it.id in selection },
                    onClear = { selection = emptySet() },
                    onSelectAll = { selection = items.mapTo(HashSet()) { it.id } },
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                TextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = {
                        Text(if (model?.multilingual == false) "Suche auf Englisch, z. B. „dog on the beach“ oder „march 2024“" else "Suche nach Inhalten, Personen, Datum …")
                    },
                    leadingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery(""); focusManager.clearFocus() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                        } else Icon(Icons.Outlined.Search, null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, "Leeren") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(32.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.setQuery(query, immediate = true); focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(60.dp)
                        .focusRequester(focus)
                )
            }
        }

        AnimatedVisibility(progress != null && ready) {
            val p = progress
            if (p != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        if (p.total > 0) "KI indexiert: ${Format.count(p.done)} von ${Format.count(p.total)}" +
                            (if (p.msPerImage > 0) " · noch ca. ${Format.etaSeconds((p.total - p.done).toLong() * p.msPerImage / 1000)}" else "")
                        else if (p.running) "KI-Indexierung wird vorbereitet …"
                        else "KI-Indexierung: " + BackgroundJobs.waitingReason(context, p.state, c.settings.current.indexOnlyWhileCharging, p.attempts),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (p.total > 0) {
                        LinearWavyProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = when {
                    query.isBlank() -> if (ready) 1 else 0
                    // Without an AI model only searches by date or person can have results.
                    !ready && result == null && !loading -> 0
                    else -> 2
                },
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                label = "search-content"
            ) { state ->
                when (state) {
                    0 -> SetupHint(onSetup = { nav.models() })
                    1 -> ExploreGrid(
                        persons = namedPersons,
                        onPerson = { vm.setQuery(it, immediate = true) },
                        explore = explore,
                        indexed = indexed,
                        total = media.size,
                        multilingual = model?.multilingual == true,
                        bottomPadding = bottom + 96.dp,
                        onConcept = { vm.setQuery(if (model?.multilingual == true) it.label else it.prompt, immediate = true) },
                        onSuggestion = { vm.setQuery(it, immediate = true) },
                    )
                    else -> {
                        val r = result
                        when {
                            r == null && loading -> Box(Modifier.fillMaxSize()) { LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp)) }
                            r == null || (r.items.isEmpty() && albums.isEmpty()) -> Column(
                                Modifier.fillMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (loading) LoadingIndicator() else {
                                    ShapeIcon(Icons.Outlined.SearchOff, size = 88.dp, shape = MaterialShapes.Cookie7Sided.toShape())
                                    Spacer(Modifier.height(16.dp))
                                    Text("Keine Treffer", style = MaterialTheme.typography.titleLarge)
                                    if (indexed == 0) Text("Die KI-Indexierung läuft noch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> MediaGrid(
                                items = r.items,
                                columns = columns,
                                onColumnsChange = { columns = it },
                                selection = selection,
                                onSelectionChange = { selection = it },
                                onOpen = { item ->
                                    val key = c.lists.register("search:${r.query}", r.items)
                                    nav.viewer(key, item.id)
                                },
                                grouping = Grouping.NONE,
                                contentPadding = PaddingValues(bottom = bottom + 96.dp),
                                header = {
                                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        if (albums.isNotEmpty()) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                                                albums.take(3).forEach { a ->
                                                    Surface(
                                                        onClick = { nav.collection(MediaListRegistry.album(a.id), a.name) },
                                                        shape = RoundedCornerShape(16.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            MediaThumbnail(a.cover, shared = false, showBadges = false, size = 128, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)))
                                                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).width(90.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            (dateLabel?.let { "$it · " } ?: "") +
                                                "${Format.count(r.items.size)} Treffer · ${r.tookMs} ms · ${Format.count(r.indexedCount)} Fotos durchsucht",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupHint(onSetup: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ShapeIcon(Icons.Outlined.AutoAwesome, size = 120.dp, shape = MaterialShapes.SoftBurst.toShape(), spin = true)
        Spacer(Modifier.height(24.dp))
        Text("KI-Suche einrichten", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Wähle ein KI-Modell – von blitzschnell bis extrem genau. Es wird einmalig geladen und läuft danach komplett offline auf deinem Pixel.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSetup, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(56.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Modell auswählen")
        }
    }
}

@Composable
private fun ExploreGrid(
    persons: List<app.lumen.photos.face.Person>,
    onPerson: (String) -> Unit,
    explore: List<Pair<Concept, MediaItem>>,
    indexed: Int,
    total: Int,
    multilingual: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onConcept: (Concept) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val suggestions = if (multilingual) {
        listOf("Hund im Schnee", "Sonnenuntergang am Meer", "Geburtstagstorte", "Rotes Auto", "Menschen lachen", "Quittung", "Berggipfel", "Essen im Restaurant", "Gestern", "Letzter Monat")
    } else {
        listOf("dog in the snow", "sunset over the sea", "birthday cake", "red car", "people laughing", "receipt", "mountain peak", "food in a restaurant", "yesterday", "last month")
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (persons.isNotEmpty()) {
                    Text("Personen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        items(persons.size) { i ->
                            val p = persons[i]
                            app.lumen.photos.ui.screens.people.PersonCard(p, Modifier.width(78.dp)) { onPerson(p.name!!) }
                        }
                    }
                }
                Text("Probier mal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        Surface(
                            onClick = { onSuggestion(s) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) { Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Entdecken", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${Format.count(indexed)} / ${Format.count(total)} indexiert",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(explore, key = { it.first.label }) { (concept, item) ->
            Surface(
                onClick = { onConcept(concept) },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.animateItem().aspectRatio(0.82f)
            ) {
                Box {
                    MediaThumbnail(item, shared = false, showBadges = false, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
                    Text(
                        concept.label,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                    )
                }
            }
        }
    }
}
