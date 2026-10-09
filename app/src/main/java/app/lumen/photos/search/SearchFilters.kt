package app.lumen.photos.search

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class MediaKind(val label: String) {
    PHOTO("Fotos"),
    VIDEO("Videos"),
    SCREENSHOT("Screenshots"),
}

enum class PlaceLevel { COUNTRY, REGION, CITY }

/** A place of the "Ort" filter: a country ("DE"), a region ("DE.02") or a city (row of the place list). */
@Immutable
data class PlaceRef(val level: PlaceLevel, val key: String, val label: String)

/**
 * The search filters. Each one is a fixed, deterministic rule – the AI only ever *suggests* values
 * for them. Different filters are combined with AND; several values of one filter with OR (except
 * persons, where [personsMatchAll] decides).
 */
@Immutable
data class SearchFilters(
    /** Inclusive date range of the capture date; either end may be open. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kinds: Set<MediaKind> = emptySet(),
    val places: Set<PlaceRef> = emptySet(),
    val persons: Set<Long> = emptySet(),
    /** true: every selected person is on the photo; false: at least one of them. */
    val personsMatchAll: Boolean = true,
    val albums: Set<Long> = emptySet(),
    val favoritesOnly: Boolean = false,
) {
    val hasDate: Boolean get() = from != null || to != null
    val isEmpty: Boolean
        get() = !hasDate && kinds.isEmpty() && places.isEmpty() && persons.isEmpty() && albums.isEmpty() && !favoritesOnly
    val count: Int
        get() = listOf(hasDate, kinds.isNotEmpty(), places.isNotEmpty(), persons.isNotEmpty(), albums.isNotEmpty(), favoritesOnly).count { it }

    fun matchesDate(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))

    fun apply(patch: FilterPatch): SearchFilters = when (patch) {
        is FilterPatch.DateRange -> copy(from = patch.from, to = patch.to)
        is FilterPatch.Kind -> copy(kinds = kinds + patch.kind)
        is FilterPatch.Place -> copy(places = places + patch.place)
        is FilterPatch.Person -> copy(persons = persons + patch.id)
        is FilterPatch.Album -> copy(albums = albums + patch.id)
        FilterPatch.Favorites -> copy(favoritesOnly = true)
    }

    /** True if applying [patch] would change nothing (the suggestion is already in effect). */
    fun contains(patch: FilterPatch): Boolean = when (patch) {
        is FilterPatch.DateRange -> from == patch.from && to == patch.to
        is FilterPatch.Kind -> patch.kind in kinds
        is FilterPatch.Place -> patch.place in places
        is FilterPatch.Person -> patch.id in persons
        is FilterPatch.Album -> patch.id in albums
        FilterPatch.Favorites -> favoritesOnly
    }

    companion object {
        private val short = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMANY)

        /** "1.6.2026 – 31.8.2026", "ab 1.1.2020", "bis 31.12.2014". */
        fun dateLabel(from: LocalDate?, to: LocalDate?): String = when {
            from != null && to != null && from == to -> short.format(from)
            from != null && to != null -> "${short.format(from)} – ${short.format(to)}"
            from != null -> "ab ${short.format(from)}"
            to != null -> "bis ${short.format(to)}"
            else -> "Datum"
        }
    }
}

/** One filter value the parser or the AI suggests; applying it adds it to the current filters. */
sealed interface FilterPatch {
    data class DateRange(val from: LocalDate?, val to: LocalDate?) : FilterPatch
    data class Kind(val kind: MediaKind) : FilterPatch
    data class Place(val place: PlaceRef) : FilterPatch
    data class Person(val id: Long, val name: String) : FilterPatch
    data class Album(val id: Long, val name: String) : FilterPatch
    data object Favorites : FilterPatch
}

@Immutable
data class FilterSuggestion(
    val patch: FilterPatch,
    /** The words of the query this filter replaces (removed from the text when applied). */
    val matched: List<String>,
    /** Suggested by the language model (shown with a sparkle) rather than by the fixed rules. */
    val fromAi: Boolean = false,
) {
    val label: String
        get() = when (patch) {
            is FilterPatch.DateRange -> SearchFilters.dateLabel(patch.from, patch.to)
            is FilterPatch.Kind -> "Nur ${patch.kind.label}"
            is FilterPatch.Place -> patch.place.label
            is FilterPatch.Person -> patch.name
            is FilterPatch.Album -> "Album „${patch.name}“"
            FilterPatch.Favorites -> "Nur Favoriten"
        }
}
