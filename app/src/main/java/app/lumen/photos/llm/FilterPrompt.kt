package app.lumen.photos.llm

import app.lumen.photos.search.FilterParser
import app.lumen.photos.search.FilterPatch
import app.lumen.photos.search.FilterSuggestion
import app.lumen.photos.search.MediaKind
import app.lumen.photos.search.ParseContext
import java.time.LocalDate
import java.util.Locale

/**
 * The language model only *suggests* values for the fixed search filters. It answers with a small
 * JSON object; a time span is given as words ("Weihnachten 2024", "Frühling 2025") that the fixed
 * date rules turn into exact dates – small models are good at understanding "vor zwei Jahren",
 * but bad at date arithmetic. Every value is checked: names must be in the lists and appear in the
 * query, a time span only counts if the query talks about time.
 */
object FilterPrompt {
    private val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    private val dayNames = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    /** Lists in the prompt are capped – they cost time on every new prompt beginning. */
    private const val MAX_NAMES = 40

    /** The fixed part of the prompt (cached by the engine): instructions, lists and examples. */
    fun prefix(ctx: ParseContext, today: LocalDate): String {
        val y = today.year
        val lastMonth = today.withDayOfMonth(1).minusDays(1)
        val persons = ctx.persons.map { it.name }.distinct().take(MAX_NAMES)
        val places = ctx.places.map { it.ref.label }.distinct().take(MAX_NAMES)
        val albums = ctx.albums.map { it.name }.distinct().take(MAX_NAMES)
        val system = buildString {
            append("Du zerlegst Suchanfragen für eine Foto-Galerie in Filter. Heute ist ${dayNames[today.dayOfWeek.value - 1]}, ")
            append("${today.dayOfMonth}. ${monthNames[today.monthValue - 1]} $y (dieses Jahr $y, letztes Jahr ${y - 1}, vorletztes Jahr ${y - 2}, ")
            append("letzter Monat ${monthNames[lastMonth.monthValue - 1]} ${lastMonth.year}).\n")
            append("Antworte nur mit JSON. Felder nur angeben, wenn die Anfrage sie nennt:\n")
            append("zeit: Zeitraum als \"Monat Jahr\", \"Jahreszeit Jahr\", \"Weihnachten Jahr\", \"Ostern Jahr\", \"Silvester Jahr\", \"Jahr\", \"Jahr bis Jahr\" oder \"TT.MM.JJJJ\"\n")
            append("typ: [\"video\"] oder [\"screenshot\"]\n")
            append("personen, orte, alben: nur Namen aus diesen Listen\n")
            append("favoriten: true bei Lieblingsfotos oder Favoriten\n")
            append("Personen: ${persons.joinToString(", ").ifEmpty { "-" }}\n")
            append("Orte: ${places.joinToString(", ").ifEmpty { "-" }}\n")
            append("Alben: ${albums.joinToString(", ").ifEmpty { "-" }}")
        }
        val examples = listOf(
            "Videos vom Strand im Sommer vor drei Jahren" to
                "{\"zeit\": \"Sommer ${y - 3}\", \"typ\": [\"video\"]}",
            "Hund im Schnee" to "{}",
            "Lieblingsfotos letzten Dezember" to
                "{\"zeit\": \"Dezember ${y - 1}\", \"favoriten\": true}",
        )
        return buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            for ((q, a) in examples) {
                append("<|im_start|>user\n").append(q).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n")
        }
    }

    fun suffix(query: String): String = "${query.trim().replace('\n', ' ')}<|im_end|>\n<|im_start|>assistant\n"

    /** True once the answer is a complete JSON object – generation can stop there. */
    fun isComplete(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        var started = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> { depth++; started = true }
                '}' -> { depth--; if (started && depth == 0) return true }
            }
        }
        return false
    }

    // ------------------------------------------------------------------ answer

    private val field = Regex("\"([\\p{L}_]+)\"\\s*:\\s*(\\[[^\\]]*]|\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+)")
    private val stringValue = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** All values per field (small models sometimes repeat a field or write "orten" for "orte"). */
    fun fields(answer: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (m in field.findAll(answer)) {
            val key = m.groupValues[1].lowercase(Locale.ROOT).let { k ->
                when {
                    k.startsWith("person") -> "personen"
                    k.startsWith("ort") -> "orte"
                    k.startsWith("album") || k.startsWith("alben") -> "alben"
                    k.startsWith("favorit") -> "favoriten"
                    k.startsWith("zeitw") -> "zeitwörter"
                    else -> k
                }
            }
            val raw = m.groupValues[2]
            val values = when {
                raw.startsWith("[") || raw.startsWith("\"") -> stringValue.findAll(raw).map { it.groupValues[1].replace("\\\"", "\"").trim() }.toList()
                else -> listOf(raw)
            }
            out.getOrPut(key) { ArrayList() }.addAll(values.filter { it.isNotEmpty() })
        }
        return out
    }

    private val timeHints = listOf(
        "jahr", "monat", "woche", "tag", "gestern", "heute", "vorgestern", "letzt", "vorig", "vergangen", "diese",
        "sommer", "winter", "herbst", "frühling", "frühjahr", "weihnacht", "ostern", "silvester", "neujahr", "urlaub", "ferien",
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember",
        "year", "month", "week", "day", "yesterday", "today", "last", "summer", "spring", "autumn", "fall", "christmas", "easter",
    )

    private val numberWords = setOf("ein", "einem", "einen", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "vor", "seit", "ago")

    private fun isTimeWord(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT).trim(',', '.', '!', '?')
        return w.isNotEmpty() && (w.any { it.isDigit() } || w in numberWords || timeHints.any { w.startsWith(it) })
    }

    /** The words of the query that describe the time span (removed when the filter is applied). */
    fun timeWords(query: String): List<String> =
        query.split(Regex("\\s+")).filter { isTimeWord(it) }.map { it.trim(',', '.', '!', '?') }

    private fun mentionsTime(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT)
        return Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)").containsMatchIn(q) || Regex("\\d{1,2}\\.\\d{1,2}\\.").containsMatchIn(q) ||
            timeHints.any { q.contains(it) }
    }

    /** A name counts if a word of the query starts like it ("Pauls" → Paul, "Österreichs" → Österreich). */
    private fun mentioned(query: String, name: String): Boolean {
        val q = query.lowercase(Locale.ROOT)
        val n = name.lowercase(Locale.ROOT)
        if (n.length >= 3 && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(n)}").containsMatchIn(q)) return true
        // Inflected forms: share the first letters with a word of the query.
        val stem = n.take(maxOf(4, n.length - 2))
        return n.length >= 5 && q.split(Regex("[^\\p{L}\\p{N}]+")).any { it.length >= stem.length && it.startsWith(stem) }
    }

    /** The occurrence of [name] in the query (for removing it when the suggestion is applied). */
    private fun occurrence(query: String, name: String): String {
        val words = query.split(Regex("\\s+"))
        val n = name.lowercase(Locale.ROOT)
        val stem = n.take(maxOf(4, n.length - 2))
        return words.firstOrNull { it.lowercase(Locale.ROOT).trim(',', '.', '!', '?').startsWith(stem) }?.trim(',', '.', '!', '?') ?: name
    }

    /** The first word of the query that contains one of [hints]. */
    private fun wordWith(query: String, hints: List<String>): List<String> =
        listOfNotNull(query.split(Regex("\\s+")).firstOrNull { w -> hints.any { w.lowercase(Locale.ROOT).contains(it) } }?.trim(',', '.', '!', '?'))

    private val videoHints = listOf("video", "film", "clip", "movie")
    private val screenshotHints = listOf("screenshot", "bildschirm")
    private val favoriteHints = listOf("lieb", "favor", "favour", "herz")

    fun suggestions(answer: String, query: String, ctx: ParseContext, today: LocalDate): List<FilterSuggestion> {
        val f = fields(answer)
        val q = query.lowercase(Locale.ROOT)
        val out = ArrayList<FilterSuggestion>()

        val time = f["zeit"]?.firstOrNull()
        if (time != null && mentionsTime(query)) {
            val range = FilterParser.parseDate(time, today)
            // A whole year only counts if the query names that year ("Monat 2026" for "letzten Monat" does not).
            val wholeYear = range?.from != null && range.to != null && range.from.dayOfYear == 1 &&
                range.to == range.from.withDayOfYear(range.from.lengthOfYear()) && !query.contains(range.from.year.toString()) &&
                !q.contains("jahr") && !q.contains("year")
            if (range != null && (range.from != null || range.to != null) && !wholeYear) {
                out += FilterSuggestion(FilterPatch.DateRange(range.from, range.to), timeWords(query), fromAi = true)
            }
        }
        for (t in f["typ"].orEmpty()) {
            val kind = when {
                t.lowercase(Locale.ROOT).startsWith("video") && videoHints.any { q.contains(it) } -> MediaKind.VIDEO
                t.lowercase(Locale.ROOT).startsWith("screen") && screenshotHints.any { q.contains(it) } -> MediaKind.SCREENSHOT
                else -> null
            } ?: continue
            out += FilterSuggestion(FilterPatch.Kind(kind), wordWith(query, if (kind == MediaKind.VIDEO) videoHints else screenshotHints), fromAi = true)
        }
        for (name in f["personen"].orEmpty()) {
            val p = ctx.persons.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            if (mentioned(query, p.name)) out += FilterSuggestion(FilterPatch.Person(p.id, p.name), listOf(occurrence(query, p.name)), fromAi = true)
        }
        for (name in f["orte"].orEmpty()) {
            val place = ctx.places.firstOrNull { c -> c.names.any { it.equals(name, ignoreCase = true) } } ?: continue
            val said = place.names.firstOrNull { mentioned(query, it) } ?: continue
            out += FilterSuggestion(FilterPatch.Place(place.ref), listOf(occurrence(query, said)), fromAi = true)
        }
        for (name in f["alben"].orEmpty()) {
            val a = ctx.albums.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            if (mentioned(query, a.name)) out += FilterSuggestion(FilterPatch.Album(a.id, a.name), listOf(occurrence(query, a.name)), fromAi = true)
        }
        if (f["favoriten"]?.firstOrNull() == "true" && favoriteHints.any { q.contains(it) }) {
            out += FilterSuggestion(FilterPatch.Favorites, wordWith(query, favoriteHints), fromAi = true)
        }
        return out.distinctBy { it.patch }
    }
}
