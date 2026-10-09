package app.lumen.photos.search

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParserTest {
    // Friday, 9 October 2026
    private val today = LocalDate.of(2026, 10, 9)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun range(q: String) = FilterParser.parseDate(q, today)?.let { it.from to it.to }

    @Test fun summerOfAYear() {
        assertEquals(d(2026, 6, 1) to d(2026, 8, 31), range("Sommer 2026"))
        assertEquals(d(2025, 6, 1) to d(2025, 8, 31), range("summer 2025"))
    }

    @Test fun seasonWithoutYearIsTheMostRecent() {
        assertEquals(d(2026, 6, 1) to d(2026, 8, 31), range("Sommer"))
        // Winter 2026 has not started yet on 9 October 2026.
        assertEquals(d(2025, 12, 1) to d(2026, 2, 28), range("Winter"))
        assertEquals(d(2026, 9, 1) to d(2026, 11, 30), range("Herbst"))
    }

    @Test fun winterSpansTheTurnOfTheYear() {
        assertEquals(d(2023, 12, 1) to d(2024, 2, 29), range("Winter 2023/24"))
    }

    @Test fun yearRanges() {
        assertEquals(d(2019, 1, 1) to d(2021, 12, 31), range("2019 bis 2021"))
        assertEquals(d(2019, 1, 1) to d(2021, 12, 31), range("zwischen 2019 und 2021"))
        assertEquals(d(2019, 1, 1) to d(2021, 12, 31), range("2019-2021"))
        assertEquals(d(2020, 1, 1) to null, range("seit 2020"))
        assertEquals(null to d(2014, 12, 31), range("vor 2015"))
    }

    @Test fun monthRanges() {
        assertEquals(d(2024, 3, 1) to d(2024, 5, 31), range("März bis Mai 2024"))
    }

    @Test fun numericRange() {
        assertEquals(d(2026, 6, 1) to d(2026, 8, 31), range("1.6.2026 - 31.8.2026"))
        assertEquals(d(2026, 6, 1) to d(2026, 8, 31), range("1.6. bis 31.8.2026"))
    }

    @Test fun singleDatesBecomeRanges() {
        assertEquals(d(2023, 7, 1) to d(2023, 7, 31), range("Strand Juli 2023"))
        assertEquals(d(2024, 3, 12) to d(2024, 3, 12), range("12.03.2024"))
        assertEquals(d(2026, 10, 8) to d(2026, 10, 8), range("gestern"))
        assertEquals(d(2023, 1, 1) to d(2023, 12, 31), range("2023"))
        // A month without year is the last one that has begun.
        assertEquals(d(2025, 12, 1) to d(2025, 12, 31), range("Dezember"))
        assertEquals(d(2026, 3, 1) to d(2026, 3, 31), range("März"))
    }

    @Test fun relativeRanges() {
        assertEquals(d(2026, 10, 3) to d(2026, 10, 9), range("letzte 7 Tage"))
        assertEquals(d(2024, 1, 1) to d(2024, 12, 31), range("vor 2 Jahren"))
    }

    @Test fun holidays() {
        assertEquals(d(2024, 12, 24) to d(2024, 12, 26), range("Weihnachten 2024"))
        assertEquals(d(2025, 12, 31) to d(2026, 1, 1), range("Silvester 2025"))
        assertEquals(d(2026, 4, 3) to d(2026, 4, 6), range("Ostern 2026"))
    }

    @Test fun noDate() {
        assertNull(FilterParser.parseDate("Hund am Strand", today))
    }

    private val ctx = ParseContext(
        persons = listOf(NamedRef(1, "Paul"), NamedRef(2, "Anna Maria")),
        places = listOf(
            PlaceCandidate(PlaceRef(PlaceLevel.CITY, "7", "Rom"), listOf("Rom", "Rome")),
            PlaceCandidate(PlaceRef(PlaceLevel.COUNTRY, "IT", "Italien"), listOf("Italien", "Italy")),
        ),
        albums = listOf(NamedRef(10, "WhatsApp Images")),
    )

    @Test fun suggestsEveryKindOfFilter() {
        val s = FilterParser.suggest("Paul und Anna Maria in Rom Sommer 2026 Videos", ctx, today)
        val patches = s.map { it.patch }
        assertTrue(FilterPatch.DateRange(d(2026, 6, 1), d(2026, 8, 31)) in patches)
        assertTrue(FilterPatch.Person(1, "Paul") in patches)
        assertTrue(FilterPatch.Person(2, "Anna Maria") in patches)
        assertTrue(FilterPatch.Place(PlaceRef(PlaceLevel.CITY, "7", "Rom")) in patches)
        assertTrue(FilterPatch.Kind(MediaKind.VIDEO) in patches)
    }

    @Test fun placesMatchOtherNamesAndWholeWordsOnly() {
        assertEquals(listOf(FilterPatch.Place(PlaceRef(PlaceLevel.COUNTRY, "IT", "Italien"))), FilterParser.suggest("Italy", ctx, today).map { it.patch })
        assertTrue(FilterParser.suggest("Romantik", ctx, today).isEmpty())
    }

    @Test fun favoritesAndAlbums() {
        val s = FilterParser.suggest("Favoriten aus WhatsApp Images", ctx, today).map { it.patch }
        assertTrue(FilterPatch.Favorites in s)
        assertTrue(FilterPatch.Album(10, "WhatsApp Images") in s)
    }

    @Test fun applyingRemovesTheWords() {
        val q = "Paul am Strand im Sommer 2026"
        val s = FilterParser.suggest(q, ctx, today)
        val rest = s.fold(q) { acc, it -> FilterParser.removeMatched(acc, it.matched) }
        assertEquals("Strand", rest)
    }

    @Test fun removingAnOnlyDateLeavesNothing() {
        val s = FilterParser.suggest("Fotos vom März 2024", ctx, today)
        assertEquals("", FilterParser.removeMatched("Fotos vom März 2024", s.first().matched))
    }

    @Test fun easterDates() {
        assertEquals(d(2024, 3, 31), FilterParser.easter(2024))
        assertEquals(d(2025, 4, 20), FilterParser.easter(2025))
        assertEquals(d(2026, 4, 5), FilterParser.easter(2026))
    }

    @Test fun numberWordsAndYearModifiers() {
        assertEquals(d(2024, 1, 1) to d(2024, 12, 31), range("vor zwei Jahren"))
        assertEquals(d(2024, 12, 24) to d(2024, 12, 26), range("Weihnachten vor zwei Jahren"))
        assertEquals(d(2025, 3, 1) to d(2025, 5, 31), range("Frühjahr letzten Jahres"))
        assertEquals(d(2024, 6, 1) to d(2024, 8, 31), range("Sommer vorletztes Jahr"))
        assertEquals(d(2025, 3, 1) to d(2025, 3, 31), range("März letzten Jahres"))
        assertEquals(d(2025, 1, 1) to d(2025, 12, 31), range("letztes Jahr"))
        assertEquals(d(2026, 9, 26) to d(2026, 10, 9), range("letzte zwei Wochen"))
    }

    @Test fun yearModifierWordsAreRemovedToo() {
        val s = FilterParser.suggest("Oma an Weihnachten vor zwei Jahren", ParseContext(), today)
        assertEquals("Oma", FilterParser.removeMatched("Oma an Weihnachten vor zwei Jahren", s.first().matched))
    }
}
