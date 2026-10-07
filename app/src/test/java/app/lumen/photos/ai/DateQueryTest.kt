package app.lumen.photos.ai

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DateQueryTest {
    // Wednesday
    private val today = LocalDate.of(2026, 10, 7)
    private fun parse(q: String) = DateQueryParser.parse(q, today)

    @Test fun plainTextHasNoDate() {
        val r = parse("Hund am Strand")
        assertNull(r.filter)
        assertEquals("Hund am Strand", r.rest)
    }

    @Test fun monthAndYear() {
        val r = parse("Strand Juli 2023")
        assertEquals(DateFilter(year = 2023, month = 7), r.filter)
        assertEquals("Strand", r.rest)
        assertTrue(r.filter!!.matches(LocalDate.of(2023, 7, 31)))
        assertFalse(r.filter!!.matches(LocalDate.of(2024, 7, 1)))
    }

    @Test fun monthWithoutYearMatchesAnyYear() {
        val r = parse("märz")
        assertEquals(DateFilter(month = 3), r.filter)
        assertEquals("", r.rest)
        assertTrue(r.filter!!.matches(LocalDate.of(2019, 3, 15)))
    }

    @Test fun germanNumericDate() {
        assertEquals(DateFilter(year = 2024, month = 3, day = 12), parse("12.03.2024").filter)
        assertEquals(DateFilter(year = 2024, month = 3, day = 2), parse("2.3.24").filter)
        assertEquals(DateFilter(month = 3, day = 12), parse("12.03.").filter)
    }

    @Test fun isoDate() {
        assertEquals(DateFilter(year = 2024, month = 3, day = 12), parse("2024-03-12").filter)
    }

    @Test fun monthSlashYear() {
        assertEquals(DateFilter(year = 2024, month = 3), parse("03/2024").filter)
        assertEquals(DateFilter(year = 2024, month = 3), parse("3.2024").filter)
    }

    @Test fun dayWithMonthName() {
        val r = parse("12. März 2024")
        assertEquals(DateFilter(year = 2024, month = 3, day = 12), r.filter)
        assertEquals("", r.rest)
    }

    @Test fun yearOnly() {
        val r = parse("Geburtstag 2022")
        assertEquals(DateFilter(year = 2022), r.filter)
        assertEquals("Geburtstag", r.rest)
    }

    @Test fun otherNumbersAreNotDates() {
        assertNull(parse("3 Hunde").filter)
        assertNull(parse("Bus 1234").filter)
    }

    @Test fun fillerWordsAreDropped() {
        val r = parse("Fotos vom Oktober 2025")
        assertEquals(DateFilter(year = 2025, month = 10), r.filter)
        assertEquals("", r.rest)
        assertEquals("Strand", parse("Strand im Juli").rest)
    }

    @Test fun yesterdayAndToday() {
        assertEquals(DateFilter(year = 2026, month = 10, day = 6), parse("gestern").filter)
        assertEquals(DateFilter(year = 2026, month = 10, day = 7), parse("Heute").filter)
        assertEquals(DateFilter(year = 2026, month = 10, day = 5), parse("vorgestern").filter)
    }

    @Test fun weeks() {
        val last = parse("letzte Woche").filter!!
        assertTrue(last.matches(LocalDate.of(2026, 9, 28)))
        assertTrue(last.matches(LocalDate.of(2026, 10, 4)))
        assertFalse(last.matches(LocalDate.of(2026, 10, 5)))
        val this_ = parse("diese Woche").filter!!
        assertTrue(this_.matches(LocalDate.of(2026, 10, 5)))
        assertFalse(this_.matches(LocalDate.of(2026, 10, 4)))
    }

    @Test fun monthsAndYearsRelative() {
        assertEquals(DateFilter(year = 2026, month = 9), parse("letzten Monat").filter)
        assertEquals(DateFilter(year = 2025, month = 12), DateQueryParser.parse("letzter Monat", LocalDate.of(2026, 1, 10)).filter)
        assertEquals(DateFilter(year = 2025), parse("letztes Jahr").filter)
        assertEquals(DateFilter(year = 2026), parse("dieses Jahr").filter)
    }

    @Test fun englishWords() {
        assertEquals(DateFilter(year = 2024, month = 3), parse("march 2024").filter)
        assertEquals(DateFilter(year = 2026, month = 10, day = 6), parse("yesterday").filter)
        assertEquals("dog", parse("dog last month").rest)
    }

    @Test fun labels() {
        assertEquals("Juli 2023", DateFilter(year = 2023, month = 7).label)
        assertEquals("12. März 2024", DateFilter(year = 2024, month = 3, day = 12).label)
        assertEquals("2022", DateFilter(year = 2022).label)
    }
}
