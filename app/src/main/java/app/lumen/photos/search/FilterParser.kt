package app.lumen.photos.search

import app.lumen.photos.ai.DateFilter
import app.lumen.photos.ai.DateQueryParser
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

data class NamedRef(val id: Long, val name: String)

/** A place that occurs in the library, with every name it can be searched by. */
data class PlaceCandidate(val ref: PlaceRef, val names: List<String>)

/** What the parser may suggest: only persons, places and albums that really exist. */
class ParseContext(
    val persons: List<NamedRef> = emptyList(),
    val places: List<PlaceCandidate> = emptyList(),
    val albums: List<NamedRef> = emptyList(),
)

/** A date range found in a query, with the words it was made of. */
data class DateRangeMatch(val from: LocalDate?, val to: LocalDate?, val matched: List<String>)

/**
 * Turns search words into filter suggestions with fixed rules – fast, offline and predictable:
 * "Sommer 2026" → 1.6.2026 – 31.8.2026, "Paul" → person Paul, "Videos" → only videos, "Rom" → the
 * place Rome (if there are photos from there). The AI suggestions are produced elsewhere and only
 * fill the same filters.
 */
object FilterParser {

    private val ic = RegexOption.IGNORE_CASE
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"

    private val months: Map<String, Int> = mapOf(
        "januar" to 1, "jänner" to 1, "january" to 1, "jan" to 1,
        "februar" to 2, "february" to 2, "feb" to 2,
        "märz" to 3, "maerz" to 3, "march" to 3, "mär" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "mai" to 5, "may" to 5,
        "juni" to 6, "june" to 6, "jun" to 6,
        "juli" to 7, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8,
        "september" to 9, "sept" to 9, "sep" to 9,
        "oktober" to 10, "october" to 10, "okt" to 10, "oct" to 10,
        "november" to 11, "nov" to 11,
        "dezember" to 12, "december" to 12, "dez" to 12, "dec" to 12,
    )
    private val monthAlt = months.keys.sortedByDescending { it.length }.joinToString("|")

    private const val YEAR = "(19[789]\\d|20\\d{2})"
    private val to = "(?:bis|-|–|—|to|until|und|and)"

    private val numberWords = mapOf(
        "ein" to 1, "einem" to 1, "einen" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "one" to 1, "a" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )
    private val num = "(\\d{1,3}|" + numberWords.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private fun number(s: String): Int = s.toIntOrNull() ?: numberWords.getValue(s.lowercase(Locale.ROOT))

    private val relativeN = Regex("$B(?:(?:in\\s+den\\s+)?letzte[nr]?|vergangene[nr]?|last|past)\\s+$num\\s+(tage?n?|wochen?|monate?n?|jahren?|days?|weeks?|months?|years?)$E", ic)
    private val yearsAgo = Regex("$B(?:vor\\s+$num\\s+jahren|$num\\s+years?\\s+ago)$E", ic)

    /** "letzten Jahres", "vorletztes Jahr", "vor zwei Jahren" … next to a season, holiday or month. */
    private val yearModifier = Regex(
        "$B(?:(vorletzte[nrs]?|vorvorige[nrs]?)\\s+jahr(?:es)?|(letzte[nrs]?|vorige[nrs]?|vergangene[nrs]?)\\s+jahr(?:es)?|(diese[nrs]?)\\s+jahr(?:es)?|last\\s+year|this\\s+year|vor\\s+$num\\s+jahren|$num\\s+years?\\s+ago)$E",
        ic,
    )
    private val monthWord = Regex("$B($monthAlt)\\.?$E", ic)
    private val numericRange = Regex("$B(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})?\\s*(?:bis|-|–|to)\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$E", ic)
    private val yearRange = Regex("$B(?:(?:von|vom|zwischen|from|between)\\s+)?$YEAR\\s*$to\\s*$YEAR$E", ic)
    private val monthRange = Regex("$B(?:(?:von|vom|zwischen|from|between)\\s+)?($monthAlt)\\.?\\s*(?:bis|-|–|to|until)\\s*($monthAlt)\\.?(?:\\s+$YEAR)?$E", ic)
    private val season = Regex("$B(sommer|summer|winter|frühling|frühjahr|fruehling|spring|herbst|autumn|fall)(?:\\s+(?:of\\s+)?$YEAR(?:\\s*/\\s*(\\d{2}|\\d{4}))?)?$E", ic)
    private val holiday = Regex("$B(weihnachten|heiligabend|christmas|silvester|neujahr|new\\s+year'?s?\\s+eve|ostern|easter)(?:\\s+$YEAR)?$E", ic)
    private val since = Regex("$B(seit|ab|since|nach|after|vor|before|bis|until)\\s+$YEAR$E", ic)

    // ------------------------------------------------------------------ dates

    fun parseDate(query: String, today: LocalDate = LocalDate.now()): DateRangeMatch? {
        relativeN.find(query)?.let { m ->
            val n = number(m.groupValues[1]).toLong()
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val from = when {
                unit.startsWith("tag") || unit.startsWith("day") -> today.minusDays(n - 1)
                unit.startsWith("woche") || unit.startsWith("week") -> today.minusDays(7 * n - 1)
                unit.startsWith("monat") || unit.startsWith("month") -> today.minusMonths(n).plusDays(1)
                else -> today.minusYears(n).plusDays(1)
            }
            return DateRangeMatch(from, today, listOf(m.value))
        }
        numericRange.find(query)?.let { m ->
            val y2 = m.groupValues[6].toInt()
            val y1 = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: y2
            val a = date(y1, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            val b = date(y2, m.groupValues[5].toInt(), m.groupValues[4].toInt())
            if (a != null && b != null) return DateRangeMatch(minOf(a, b), maxOf(a, b), listOf(m.value))
        }
        yearRange.find(query)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (b >= a) return DateRangeMatch(LocalDate.of(a, 1, 1), LocalDate.of(b, 12, 31), listOf(m.value))
        }
        monthRange.find(query)?.let { m ->
            val a = months.getValue(m.groupValues[1].lowercase(Locale.ROOT))
            val b = months.getValue(m.groupValues[2].lowercase(Locale.ROOT))
            val yEnd = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
                ?: (if (YearMonth.of(today.year, b) <= YearMonth.from(today)) today.year else today.year - 1)
            val yStart = if (a <= b) yEnd else yEnd - 1
            return DateRangeMatch(LocalDate.of(yStart, a, 1), YearMonth.of(yEnd, b).atEndOfMonth(), listOf(m.value))
        }
        val modifier = yearModifier.find(query)
        val modYear: Int? = modifier?.let { m ->
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> today.year - 2
                g[2].isNotEmpty() -> today.year - 1
                g[3].isNotEmpty() -> today.year
                g[4].isNotEmpty() -> today.year - number(g[4])
                g[5].isNotEmpty() -> today.year - number(g[5])
                m.value.lowercase(Locale.ROOT).startsWith("last") -> today.year - 1
                else -> today.year
            }
        }
        fun withModifier(m: MatchResult, explicitYear: Boolean): List<String> =
            if (!explicitYear && modifier != null) listOf(m.value, modifier.value) else listOf(m.value)

        season.find(query)?.let { m ->
            val name = m.groupValues[1].lowercase(Locale.ROOT)
            val year = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: modYear
            val (startMonth, length) = when (name) {
                "sommer", "summer" -> 6 to 3
                "winter" -> 12 to 3
                "herbst", "autumn", "fall" -> 9 to 3
                else -> 3 to 3
            }
            // "Winter 2025/26" (and plain "Winter 2026", meaning Jan/Feb 2026 belong to it too –
            // a winter is named after the year it starts in, unless a second year says otherwise).
            val startYear = when {
                year == null -> {
                    val thisYear = LocalDate.of(today.year, startMonth, 1)
                    if (!thisYear.isAfter(today)) today.year else today.year - 1
                }
                else -> year
            }
            val from = LocalDate.of(startYear, startMonth, 1)
            val end = YearMonth.from(from).plusMonths(length - 1L).atEndOfMonth()
            return DateRangeMatch(from, end, withModifier(m, m.groupValues[2].isNotEmpty()))
        }
        holiday.find(query)?.let { m ->
            val name = m.groupValues[1].lowercase(Locale.ROOT)
            val explicit = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: modYear
            fun range(y: Int): Pair<LocalDate, LocalDate> = when {
                name.startsWith("weih") || name.startsWith("heilig") || name.startsWith("christ") ->
                    LocalDate.of(y, 12, 24) to LocalDate.of(y, 12, 26)
                name.startsWith("silv") || name.startsWith("new") -> LocalDate.of(y, 12, 31) to LocalDate.of(y + 1, 1, 1)
                name.startsWith("neuj") -> LocalDate.of(y, 1, 1) to LocalDate.of(y, 1, 1)
                else -> easter(y).let { it.minusDays(2) to it.plusDays(1) }
            }
            val y = explicit ?: (if (!range(today.year).first.isAfter(today)) today.year else today.year - 1)
            val (from, end) = range(y)
            return DateRangeMatch(from, end, withModifier(m, m.groupValues[2].isNotEmpty()))
        }
        // "März letzten Jahres", "im Mai vor drei Jahren".
        if (modYear != null) monthWord.find(query)?.let { m ->
            val ym = YearMonth.of(modYear, months.getValue(m.groupValues[1].lowercase(Locale.ROOT)))
            return DateRangeMatch(ym.atDay(1), ym.atEndOfMonth(), listOf(m.value, modifier!!.value))
        }
        yearsAgo.find(query)?.let { m ->
            val y = today.year - number(m.groupValues[1].ifEmpty { m.groupValues[2] })
            return DateRangeMatch(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), listOf(m.value))
        }
        since.find(query)?.let { m ->
            val y = m.groupValues[2].toInt()
            return when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "seit", "ab", "since" -> DateRangeMatch(LocalDate.of(y, 1, 1), null, listOf(m.value))
                "nach", "after" -> DateRangeMatch(LocalDate.of(y + 1, 1, 1), null, listOf(m.value))
                "vor", "before" -> DateRangeMatch(null, LocalDate.of(y - 1, 12, 31), listOf(m.value))
                else -> DateRangeMatch(null, LocalDate.of(y, 12, 31), listOf(m.value))
            }
        }
        // Single dates ("März 2024", "12.03.2024", "gestern", "2023" …).
        val single = DateQueryParser.parse(query, today)
        val filter = single.filter ?: return null
        val range = toRange(filter, today) ?: return null
        return DateRangeMatch(range.first, range.second, removedWords(query, single.rest))
    }

    /** A partial date as a range; without a year the most recent occurrence is meant. */
    fun toRange(f: DateFilter, today: LocalDate): Pair<LocalDate, LocalDate>? {
        if (f.from != null || f.to != null) return (f.from ?: f.to!!) to (f.to ?: f.from!!)
        val y = f.year
        val m = f.month
        val d = f.day
        return when {
            y != null && m != null && d != null -> date(y, m, d)?.let { it to it }
            y != null && m != null -> YearMonth.of(y, m).let { it.atDay(1) to it.atEndOfMonth() }
            y != null -> LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31)
            m != null && d != null -> {
                val thisYear = date(today.year, m, d)
                val day = if (thisYear != null && !thisYear.isAfter(today)) thisYear else date(today.year - 1, m, d)
                day?.let { it to it }
            }
            m != null -> {
                val ym = if (YearMonth.of(today.year, m) <= YearMonth.from(today)) YearMonth.of(today.year, m) else YearMonth.of(today.year - 1, m)
                ym.atDay(1) to ym.atEndOfMonth()
            }
            else -> null
        }
    }

    /** Easter Sunday (anonymous Gregorian algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** The words of [query] that are no longer in [rest]. */
    private fun removedWords(query: String, rest: String): List<String> {
        val left = rest.split(' ').filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }.toMutableList()
        val out = ArrayList<String>()
        for (w in query.split(' ').filter { it.isNotEmpty() }) {
            if (!left.remove(w.lowercase(Locale.ROOT))) out += w
        }
        return out
    }

    // ------------------------------------------------------------------ everything else

    private val kindWords = listOf(
        MediaKind.VIDEO to listOf("videos", "video", "filme", "film", "clips", "clip", "movies", "movie"),
        MediaKind.SCREENSHOT to listOf("bildschirmfotos", "bildschirmfoto", "screenshots", "screenshot"),
        MediaKind.PHOTO to listOf("nur fotos", "nur bilder", "only photos", "photos only"),
    )
    private val favoriteWords = listOf("lieblingsfotos", "lieblingsbilder", "favoriten", "favorites", "favourites", "favorit", "favorite", "favourite")

    fun suggest(query: String, ctx: ParseContext, today: LocalDate = LocalDate.now()): List<FilterSuggestion> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<FilterSuggestion>()
        val lower = query.lowercase(Locale.ROOT)
        val taken = ArrayList<IntRange>()
        fun claim(phrase: String): String? {
            val range = findPhrase(lower, phrase.lowercase(Locale.ROOT), taken) ?: return null
            taken += range
            return query.substring(range)
        }

        parseDate(query, today)?.let { d ->
            d.matched.forEach { w -> findPhrase(lower, w.lowercase(Locale.ROOT), taken)?.let { taken += it } }
            out += FilterSuggestion(FilterPatch.DateRange(d.from, d.to), d.matched)
        }
        for (p in ctx.persons.sortedByDescending { it.name.length }) {
            claim(p.name)?.let { out += FilterSuggestion(FilterPatch.Person(p.id, p.name), listOf(it)) }
        }
        for ((kind, words) in kindWords) {
            val hit = words.firstNotNullOfOrNull { claim(it) } ?: continue
            out += FilterSuggestion(FilterPatch.Kind(kind), listOf(hit))
        }
        favoriteWords.firstNotNullOfOrNull { claim(it) }?.let { out += FilterSuggestion(FilterPatch.Favorites, listOf(it)) }
        val placeNames = ctx.places.flatMap { c -> c.names.map { it to c.ref } }
            .filter { it.first.length >= 3 }
            .sortedByDescending { it.first.length }
        val seenPlaces = HashSet<PlaceRef>()
        for ((name, ref) in placeNames) {
            if (ref in seenPlaces) continue
            claim(name)?.let { seenPlaces += ref; out += FilterSuggestion(FilterPatch.Place(ref), listOf(it)) }
        }
        for (a in ctx.albums.filter { it.name.length >= 3 }.sortedByDescending { it.name.length }) {
            claim(a.name)?.let { out += FilterSuggestion(FilterPatch.Album(a.id, a.name), listOf(it)) }
        }
        return out
    }

    /** Finds [phrase] as whole words in [text] (both lower case), outside the [taken] ranges. */
    private fun findPhrase(text: String, phrase: String, taken: List<IntRange>): IntRange? {
        if (phrase.isBlank()) return null
        var from = 0
        while (true) {
            val i = text.indexOf(phrase, from)
            if (i < 0) return null
            val end = i + phrase.length
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val after = end == text.length || !text[end].isLetterOrDigit()
            val range = i until end
            if (before && after && taken.none { it.first <= range.last && range.first <= it.last }) return range
            from = i + 1
        }
    }

    /** Small words that only connect the filter words to the rest ("Paul *und* Anna *am* Strand"). */
    private val connectors = setOf(
        "und", "mit", "von", "vom", "aus", "im", "in", "am", "an", "um", "bei", "beim", "zu", "zur", "zum", "der", "die", "das", "den", "dem", "des",
        "and", "with", "from", "of", "on", "at", "the", "during", "&", ",",
        "foto", "fotos", "bild", "bilder", "photo", "photos", "picture", "pictures", "aufnahmen", "nur", "only", "alle", "all",
    )

    /** The query with the words of an applied suggestion taken out. */
    fun removeMatched(query: String, matched: List<String>): String {
        var text = query
        for (phrase in matched) {
            val range = findPhrase(text.lowercase(Locale.ROOT), phrase.lowercase(Locale.ROOT), emptyList()) ?: continue
            text = text.removeRange(range)
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // Connectors left alone at the edges or doubled in the middle go too.
        while (words.isNotEmpty() && words.first().lowercase(Locale.ROOT).trim(',') in connectors) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase(Locale.ROOT).trim(',') in connectors) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}
