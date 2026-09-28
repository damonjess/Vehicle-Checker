package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the extra detail the MOT card now derives: expiry countdown, history totals and the
 * recurring-issue grouping that surfaces faults flagged on more than one test.
 */
class MotHistoryDataTest {

    private fun record(
        date: String,
        result: String,
        advisories: List<String> = emptyList(),
        failures: List<String> = emptyList()
    ) = MotTestRecord(dateTested = date, result = result, advisories = advisories, failures = failures)

    /** Tests are held newest-first, exactly as the MOT service lists them. */
    private val history = MotHistoryData(
        motValidUntil = "13 February 2027",
        tests = listOf(
            record("23 January 2026", "PASS", advisories = listOf("Nearside front tyre worn close to legal limit (5.2.3 (e))")),
            record("14 February 2025", "FAIL", failures = listOf("Offside headlamp aim too low")),
            record("10 February 2024", "PASS", advisories = listOf("Nearside front tyre worn close to legal limit")),
            record("5 February 2023", "FAIL", failures = listOf("Offside headlamp aim too low")),
        )
    )

    @Test
    fun recurringIssuesGroupWordingVariantsAcrossYears() {
        val issues = history.recurringIssues

        val tyre = issues.first { it.text.contains("tyre", ignoreCase = true) }
        assertEquals(2, tyre.occurrences)
        assertEquals(listOf(2026, 2024), tyre.years)
        assertFalse(tyre.isFailure)

        val headlamp = issues.first { it.text.contains("headlamp", ignoreCase = true) }
        assertEquals(2, headlamp.occurrences)
        assertEquals(listOf(2025, 2023), headlamp.years)
        assertTrue(headlamp.isFailure)
    }

    @Test
    fun oneOffIssuesAreNotRecurring() {
        val oneOff = MotHistoryData(
            tests = listOf(
                record("23 January 2026", "FAIL", failures = listOf("Offside front brake disc worn")),
                record("14 February 2025", "PASS"),
            )
        )
        assertTrue(oneOff.recurringIssues.isEmpty())
    }

    @Test
    fun totalsAndHistoryRangeDescribeTheWholeTrail() {
        // Two advisories (both on the tyre) and two defects (both headlamp aim)
        assertEquals(2, history.totalFailureCount)
        assertEquals(2, history.totalAdvisoryCount)
        assertEquals("5 February 2023", history.firstTestDate)
        // Both passes carried an advisory, so neither counts as a clean pass
        assertEquals(0, history.passCount)
        assertEquals(2, history.passWithAdvisoriesCount)
        assertEquals(2, history.failCount)
    }

    @Test
    fun expiryCountdownCountsWholeDaysFromAnInjectedNow() {
        val day = MotHistoryData.parseUkDate("1 February 2027")!!
        assertEquals(12, history.daysUntilMotExpiry(day.time))
        assertEquals(0, history.daysUntilMotExpiry(MotHistoryData.parseUkDate("13 February 2027")!!.time))
        assertEquals(-1, history.daysUntilMotExpiry(MotHistoryData.parseUkDate("14 February 2027")!!.time))
    }

    @Test
    fun missingOrUnreadableExpiryYieldsNoCountdown() {
        assertNull(MotHistoryData(motValidUntil = "").motExpiryDate)
        assertNull(MotHistoryData(motValidUntil = "not a date").daysUntilMotExpiry())
    }

    @Test
    fun summaryLineNamesTheVehicleAndCountsDownToExpiry() {
        val mot = MotHistoryData(
            make = "TOYOTA",
            model = "RAV4",
            motValidUntil = "13 February 2027",
            tests = listOf(record("23 January 2026", "PASS"), record("14 February 2025", "FAIL"))
        )
        val now = MotHistoryData.parseUkDate("1 February 2027")!!.time
        assertEquals(
            "TOYOTA RAV4 · 2 tests · valid until 13 February 2027 (in 12 days)",
            mot.summaryLine(now)
        )
    }

    /** A record with no make/model and no expiry must still read sensibly on the report. */
    @Test
    fun summaryLineFallsBackWhenIdentityIsMissing() {
        val mot = MotHistoryData(tests = listOf(record("1 January 2026", "PASS")))
        assertEquals("MOT history · 1 test", mot.summaryLine(0L))
    }

    @Test
    fun overviewLineTotalsTheOutcomes() {
        val mot = MotHistoryData(
            tests = listOf(
                record("23 January 2026", "PASS"),
                record("14 February 2025", "PASS", advisories = listOf("Nearside front tyre worn")),
                record("10 February 2024", "FAIL"),
            )
        )
        assertEquals("Pass rate 67% · 1 clean pass · 1 with advisories · 1 fail", mot.overviewLine())
    }

    @Test
    fun abbreviatedMonthIsAlsoAccepted() {
        val parsed = MotHistoryData.parseUkDate("13 Feb 2027")
        assertTrue(parsed != null)
    }
}
