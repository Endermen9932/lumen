package app.lumen.photos.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A date restriction found in a search query, e.g. "März 2024", "12.03.2024" or "letzte Woche". */
data class DateFilter(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    /** Inclusive range, used for "diese/letzte Woche". */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    fun matches(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) &&
            (to == null || !date.isAfter(to)) &&
            (year == null || date.year == year) &&
            (month == null || date.monthValue == month) &&
            (day == null || date.dayOfMonth == day)

    /** Human readable form for the result header. */
    val label: String
        get() = when {
            from != null && to != null -> "${shortFormat.format(from)} – ${shortFormat.format(to)}"
            day != null && month != null && year != null -> fullFormat.format(LocalDate.of(year, month, day))
            day != null && month != null -> "$day. ${monthName(month)}"
            month != null && year != null -> "${monthName(month)} $year"
            month != null -> monthName(month)
            year != null -> year.toString()
            else -> ""
        }

    private fun monthName(m: Int) = LocalDate.of(2000, m, 1).month.getDisplayName(TextStyle.FULL, Locale.GERMANY)

    private companion object {
        val shortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMANY)
        val fullFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMANY)
    }
}

/** [filter] is null if the query contains no date; [rest] is the query without the date words. */
data class DateQuery(val filter: DateFilter?, val rest: String)

/**
 * Pulls dates out of a search query so "Strand Juli 2023" becomes the filter "Juli 2023" and the
 * text "Strand". Understands German and English month names, numeric dates (12.03.2024,
 * 2024-03-12, 03/2024), plain years and relative words (gestern, letzte Woche, letzten Monat …).
 */
object DateQueryParser {
    private val months: Map<String, Int> = buildMap {
        fun add(m: Int, vararg names: String) = names.forEach { put(it, m) }
        add(1, "januar", "jänner", "january", "jan")
        add(2, "februar", "february", "feb")
        add(3, "märz", "maerz", "march", "mär", "mar")
        add(4, "april", "apr")
        add(5, "mai", "may")
        add(6, "juni", "june", "jun")
        add(7, "juli", "july", "jul")
        add(8, "august", "aug")
        add(9, "september", "sept", "sep")
        add(10, "oktober", "october", "okt", "oct")
        add(11, "november", "nov")
        add(12, "dezember", "december", "dez", "dec")
    }
    private val monthAlt = months.keys.sortedByDescending { it.length }.joinToString("|")

    // Word boundaries that also work for umlauts and digits.
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val ic = RegexOption.IGNORE_CASE

    private val iso = Regex("$B(\\d{4})-(\\d{1,2})-(\\d{1,2})$E")
    private val dmy = Regex("$B(\\d{1,2})\\.\\s?(\\d{1,2})\\.(?:\\s?(\\d{4}|\\d{2})(?![\\p{N}]))?")
    private val my = Regex("$B(\\d{1,2})[./](\\d{4})$E")
    private val dayMonth = Regex("$B(\\d{1,2})\\.?\\s*($monthAlt)\\.?$E", ic)
    private val monthOnly = Regex("$B($monthAlt)\\.?$E", ic)
    private val yearOnly = Regex("$B(19[789]\\d|20\\d{2})$E")

    /** Words that only connect a date to the rest ("Fotos vom März") – dropped at the ends. */
    private val filler = setOf("von", "vom", "aus", "im", "in", "am", "um", "an", "from", "of", "on", "at")
    private val generic = setOf("foto", "fotos", "bild", "bilder", "photo", "photos", "picture", "pictures", "image", "images", "aufnahmen")

    private class Relative(val pattern: String, val apply: (LocalDate) -> DateFilter)

    private val relatives = listOf(
        Relative("vorgestern|day before yesterday") { today -> day(today.minusDays(2)) },
        Relative("gestern|yesterday") { today -> day(today.minusDays(1)) },
        Relative("heute|today") { today -> day(today) },
        Relative("(?:letzte|vergangene|vorige)[nrs]?\\s+woche|last\\s+week") { today -> week(today.minusWeeks(1)) },
        Relative("diese[nrs]?\\s+woche|this\\s+week") { today -> week(today) },
        Relative("(?:letzte|vergangene|vorige)[nrs]?\\s+monat|last\\s+month") { today -> today.minusMonths(1).let { DateFilter(year = it.year, month = it.monthValue) } },
        Relative("diese[nrs]?\\s+monat|this\\s+month") { today -> DateFilter(year = today.year, month = today.monthValue) },
        Relative("(?:letzte|vergangene|vorige)[nrs]?\\s+jahr|last\\s+year") { today -> DateFilter(year = today.year - 1) },
        Relative("diese[nrs]?\\s+jahr|this\\s+year") { today -> DateFilter(year = today.year) },
    ).map { it to Regex("$B(?:${it.pattern})$E", ic) }

    private fun day(d: LocalDate) = DateFilter(year = d.year, month = d.monthValue, day = d.dayOfMonth)

    private fun week(anyDay: LocalDate): DateFilter {
        val monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DateFilter(from = monday, to = monday.plusDays(6))
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun parse(query: String, today: LocalDate = LocalDate.now()): DateQuery {
        var text = " ${query.trim()} "
        var filter: DateFilter? = null

        fun cut(m: MatchResult) { text = text.replaceRange(m.range, " ") }

        for ((rule, regex) in relatives) {
            val m = regex.find(text) ?: continue
            filter = rule.apply(today)
            cut(m)
            break
        }

        if (filter == null) iso.find(text)?.let { m ->
            val d = date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (d != null) { filter = day(d); cut(m) }
        }
        if (filter == null) dmy.find(text)?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            val valid = mm in 1..12 && dd in 1..31 && (y == null || date(y, mm, dd) != null)
            if (valid) { filter = DateFilter(year = y, month = mm, day = dd); cut(m) }
        }
        if (filter == null) my.find(text)?.let { m ->
            val mm = m.groupValues[1].toInt()
            if (mm in 1..12) { filter = DateFilter(year = m.groupValues[2].toInt(), month = mm); cut(m) }
        }
        if (filter == null) dayMonth.find(text)?.let { m ->
            val dd = m.groupValues[1].toInt()
            if (dd in 1..31) { filter = DateFilter(month = months[m.groupValues[2].lowercase(Locale.ROOT)], day = dd); cut(m) }
        }
        if (filter == null) monthOnly.find(text)?.let { m ->
            filter = DateFilter(month = months[m.groupValues[1].lowercase(Locale.ROOT)])
            cut(m)
        }
        // A year can accompany a day/month ("12. März 2024") or stand alone ("2024").
        val current = filter
        if ((current == null || (current.year == null && current.from == null)) ) {
            yearOnly.find(text)?.let { m ->
                filter = (current ?: DateFilter()).copy(year = m.groupValues[1].toInt())
                cut(m)
            }
        }

        val found = filter ?: return DateQuery(null, query.trim())
        val words = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase(Locale.ROOT) in filler) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase(Locale.ROOT) in filler) words.removeAt(words.size - 1)
        if (words.all { it.lowercase(Locale.ROOT) in generic }) words.clear()
        return DateQuery(found, words.joinToString(" "))
    }
}
