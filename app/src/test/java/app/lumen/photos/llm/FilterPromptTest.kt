package app.lumen.photos.llm

import app.lumen.photos.search.FilterPatch
import app.lumen.photos.search.MediaKind
import app.lumen.photos.search.NamedRef
import app.lumen.photos.search.ParseContext
import app.lumen.photos.search.PlaceCandidate
import app.lumen.photos.search.PlaceLevel
import app.lumen.photos.search.PlaceRef
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model's answers are only suggestions: everything it says is checked against the query. */
class FilterPromptTest {
    private val today = LocalDate.of(2026, 10, 9)
    private val austria = PlaceRef(PlaceLevel.COUNTRY, "AT", "Österreich")
    private val ctx = ParseContext(
        persons = listOf(NamedRef(1, "Paul"), NamedRef(2, "Anna")),
        places = listOf(PlaceCandidate(austria, listOf("Österreich", "Austria"))),
    )

    private fun patches(answer: String, query: String) = FilterPrompt.suggestions(answer, query, ctx, today).map { it.patch }

    @Test fun timeIsTurnedIntoDatesByTheRules() {
        val p = patches("""{"zeit": "Weihnachten 2024"}""", "Oma an Weihnachten vor zwei Jahren")
        assertEquals(listOf(FilterPatch.DateRange(LocalDate.of(2024, 12, 24), LocalDate.of(2024, 12, 26))), p)
    }

    @Test fun inventedValuesAreDropped() {
        // Nothing in "Katze auf dem Sofa" is a time, a place, a person or a screenshot.
        assertTrue(patches("""{"zeit": "Januar 2026", "orten": ["Österreich"], "typ": ["screenshot"], "personen": ["Paul"]}""", "Katze auf dem Sofa").isEmpty())
    }

    @Test fun inflectedNamesAndSloppyKeys() {
        val p = patches("""{"orten": "Österreich", "personen": "Paul", "personen": "Anna", "typ": ["video"]}""", "Pauls und Annas Videos in Österreich")
        assertTrue(FilterPatch.Person(1, "Paul") in p)
        assertTrue(FilterPatch.Person(2, "Anna") in p)
        assertTrue(FilterPatch.Place(austria) in p)
        assertTrue(FilterPatch.Kind(MediaKind.VIDEO) in p)
    }

    @Test fun wholeYearOnlyIfTheQueryMeansIt() {
        assertTrue(patches("""{"zeit": "Monat 2026"}""", "Screenshots aus dem letzten Monat").none { it is FilterPatch.DateRange })
    }

    @Test fun completeJsonStopsGeneration() {
        assertFalse(FilterPrompt.isComplete("""{"zeit": "Sommer {2026"""))
        assertTrue(FilterPrompt.isComplete("""{"zeit": "Sommer 2026"}"""))
    }

    @Test fun timeWordsAreTheOnesToRemove() {
        assertEquals(listOf("Weihnachten", "vor", "zwei", "Jahren"), FilterPrompt.timeWords("Fotos von Oma an Weihnachten vor zwei Jahren"))
        assertEquals(listOf("März"), FilterPrompt.timeWords("Pauls Geburtstag im März"))
    }
}
