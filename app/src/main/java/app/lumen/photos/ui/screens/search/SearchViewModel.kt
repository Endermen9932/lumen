package app.lumen.photos.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lumen.photos.AppContainer
import app.lumen.photos.ai.SearchResult
import app.lumen.photos.data.media.Album
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.search.FilterParser
import app.lumen.photos.search.FilterSuggestion
import app.lumen.photos.search.MediaKind
import app.lumen.photos.search.NamedRef
import app.lumen.photos.search.ParseContext
import app.lumen.photos.search.SearchFilters
import app.lumen.photos.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class Concept(val label: String, val prompt: String)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _filters = MutableStateFlow(SearchFilters())
    val filters = _filters.asStateFlow()
    private val _result = MutableStateFlow<SearchResult?>(null)
    val result = _result.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums = _albums.asStateFlow()
    private val _explore = MutableStateFlow<List<Pair<Concept, MediaItem>>>(emptyList())
    val explore = _explore.asStateFlow()

    /** Filter suggestions for the current text: fixed rules right away, the AI's a moment later. */
    private val _suggestions = MutableStateFlow<List<FilterSuggestion>>(emptyList())
    val suggestions = _suggestions.asStateFlow()
    private val _aiThinking = MutableStateFlow(false)
    val aiThinking = _aiThinking.asStateFlow()

    /** Text search needs an AI model; filters work without one. */
    private val _needsModel = MutableStateFlow(false)
    val needsModel = _needsModel.asStateFlow()

    private var searchJob: Job? = null
    private var aiJob: Job? = null
    private var exploreModel: String? = null
    private var exploreSize = -1

    init {
        c.ai.warmUp()
        if (c.llm.enabledAndReady()) c.llm.warmUp(parseContext())
    }

    fun parseContext(): ParseContext = ParseContext(
        persons = c.faces.persons.value.filter { it.name != null && !it.hidden }.map { NamedRef(it.id, it.name!!) },
        places = c.locations.candidates(),
        albums = c.media.albums.value.map { NamedRef(it.id, it.name) },
    )

    fun setQuery(q: String, immediate: Boolean = false) {
        _query.value = q
        val ctx = parseContext()
        val rules = FilterParser.suggest(q, ctx).filterNot { _filters.value.contains(it.patch) }
        _suggestions.value = rules
        requestAiSuggestions(q, ctx)
        search(immediate)
    }

    fun setFilters(f: SearchFilters) {
        _filters.value = f
        _suggestions.value = _suggestions.value.filterNot { f.contains(it.patch) }
        search(immediate = true)
    }

    fun updateFilters(transform: (SearchFilters) -> SearchFilters) = setFilters(transform(_filters.value))

    /** Applies a suggestion: the filter is set and its words leave the search text. */
    fun apply(s: FilterSuggestion) {
        _filters.value = _filters.value.apply(s.patch)
        _query.value = FilterParser.removeMatched(_query.value, s.matched)
        _suggestions.value = _suggestions.value.filter { it !== s && !_filters.value.contains(it.patch) }
        search(immediate = true)
    }

    fun applyAll() {
        val all = _suggestions.value
        var f = _filters.value
        var q = _query.value
        for (s in all) {
            if (f.contains(s.patch)) continue
            f = f.apply(s.patch)
            q = FilterParser.removeMatched(q, s.matched)
        }
        _filters.value = f
        _query.value = q
        _suggestions.value = emptyList()
        search(immediate = true)
    }

    /** A tapped example ("Gestern", a person …): if the rules understand all of it, filter directly. */
    fun quickSearch(text: String) {
        val rules = FilterParser.suggest(text, parseContext())
        val rest = rules.fold(text) { q, s -> FilterParser.removeMatched(q, s.matched) }
        if (rules.isNotEmpty() && rest.isBlank()) {
            var f = _filters.value
            rules.forEach { f = f.apply(it.patch) }
            _filters.value = f
            _query.value = ""
            _suggestions.value = emptyList()
            search(immediate = true)
        } else {
            setQuery(text, immediate = true)
        }
    }

    fun clear() {
        _query.value = ""
        _filters.value = SearchFilters()
        _suggestions.value = emptyList()
        aiJob?.cancel()
        _aiThinking.value = false
        search(immediate = true)
    }

    private fun requestAiSuggestions(q: String, ctx: ParseContext) {
        aiJob?.cancel()
        _aiThinking.value = false
        if (q.isBlank() || !c.llm.enabledAndReady()) return
        aiJob = viewModelScope.launch {
            // Only once the user stops typing – the language model needs a few seconds.
            delay(900)
            _aiThinking.value = true
            try {
                val ai = runCatching { c.llm.suggestFilters(q, ctx, LocalDate.now()) }.getOrDefault(emptyList())
                if (_query.value != q) return@launch
                val current = _suggestions.value
                // The fixed rules know dates best: an AI date only counts where they found none.
                val ruleDate = current.any { it.patch is app.lumen.photos.search.FilterPatch.DateRange }
                val extra = ai.filter { s ->
                    current.none { it.patch == s.patch } && !_filters.value.contains(s.patch) &&
                        !(ruleDate && s.patch is app.lumen.photos.search.FilterPatch.DateRange)
                }
                // Rule suggestions first – the AI adds what the rules did not see.
                _suggestions.value = current + extra
            } finally {
                _aiThinking.value = false
            }
        }
    }

    private fun search(immediate: Boolean) {
        searchJob?.cancel()
        val q = _query.value.trim()
        val f = _filters.value
        if (q.isBlank() && f.isEmpty) {
            _result.value = null
            _albums.value = emptyList()
            _loading.value = false
            _needsModel.value = false
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(380)
            _loading.value = true
            val lower = q.lowercase()
            _albums.value = if (q.isBlank()) emptyList() else c.media.albums.value.filter { it.name.lowercase().contains(lower) }
            val label = listOf(q, f.hashCode().toString()).joinToString("|")
            val model = c.ai.activeModel.value
            val hasModel = model != null && c.models.isInstalled(model)
            _needsModel.value = q.isNotBlank() && !hasModel
            _result.value = runCatching {
                val allowed = if (f.isEmpty) null else withContext(Dispatchers.Default) { allowedIds(f) }
                when {
                    q.isBlank() || !hasModel -> {
                        val ids = allowed ?: emptySet()
                        val items = c.media.media.value.filter { it.id in ids }
                        SearchResult(label, items, emptyMap(), 0, items.size)
                    }
                    allowed == null -> c.ai.search(q)?.copy(query = label)
                    else -> c.ai.searchFiltered(q, allowed, label)
                }
            }.getOrNull()
            _loading.value = false
        }
    }

    /** Applies the fixed filter rules to the whole library. */
    private fun allowedIds(f: SearchFilters): Set<Long> {
        val persons = c.faces.persons.value
        val personSets = f.persons.map { id -> persons.firstOrNull { it.id == id }?.mediaIds?.toHashSet() ?: emptySet() }
        val personIds: Set<Long>? = when {
            personSets.isEmpty() -> null
            f.personsMatchAll -> personSets.reduce { a, b -> a intersect b }
            else -> personSets.fold(HashSet()) { a, b -> a.apply { addAll(b) } }
        }
        val cityOf = c.locations.cityOf.value
        val out = HashSet<Long>()
        for (item in c.media.media.value) {
            if (personIds != null && item.id !in personIds) continue
            if (f.favoritesOnly && !item.isFavorite) continue
            if (f.albums.isNotEmpty() && item.bucketId !in f.albums) continue
            if (f.kinds.isNotEmpty()) {
                val kind = when {
                    item.isVideo -> MediaKind.VIDEO
                    item.isScreenshot -> MediaKind.SCREENSHOT
                    else -> MediaKind.PHOTO
                }
                if (kind !in f.kinds) continue
            }
            if (f.hasDate && !f.matchesDate(Format.localDate(item.timestamp))) continue
            if (f.places.isNotEmpty() && f.places.none { c.locations.matches(item.id, it, cityOf) }) continue
            out += item.id
        }
        return out
    }

    fun loadExplore(concepts: (Boolean) -> List<Concept>) {
        val model = c.ai.activeModel.value ?: return
        val size = c.index.size.value
        if (size < 20 || (exploreModel == model.id && kotlin.math.abs(size - exploreSize) < 50)) return
        exploreModel = model.id
        exploreSize = size
        viewModelScope.launch {
            val out = ArrayList<Pair<Concept, MediaItem>>()
            val used = HashSet<Long>()
            for (concept in concepts(model.multilingual)) {
                val hit = runCatching { c.ai.bestMatch(concept.prompt) }.getOrNull() ?: continue
                if (used.add(hit.id)) {
                    out += concept to hit
                    _explore.value = out.toList()
                }
            }
        }
    }
}
