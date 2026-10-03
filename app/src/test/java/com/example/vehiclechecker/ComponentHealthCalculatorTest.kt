package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ComponentHealthCalculatorTest {

    private fun testRecord(
        dateTested: String = "1 March 2026",
        result: String = "PASS",
        advisories: List<String> = emptyList(),
        failures: List<String> = emptyList()
    ) = MotTestRecord(
        dateTested = dateTested,
        result = result,
        advisories = advisories,
        failures = failures
    )

    @Test
    fun cleanMotHistoryYields100PercentHealthAcrossAllDomains() {
        val history = MotHistoryData(
            registration = "AB12CDE",
            tests = listOf(
                testRecord(dateTested = "1 March 2026"),
                testRecord(dateTested = "1 March 2025")
            )
        )

        val report = ComponentHealthCalculator.calculate(history)

        assertEquals(100, report.overallScore)
        assertEquals(ComponentHealthCalculator.ComponentStatus.GREEN, report.overallStatus)
        assertEquals(5, report.components.size)
        report.components.forEach { comp ->
            assertEquals(100, comp.score)
            assertEquals(ComponentHealthCalculator.ComponentStatus.GREEN, comp.status)
        }
    }

    @Test
    fun categorizesBrakeAndTyreAdvisoriesCorrectly() {
        val history = MotHistoryData(
            registration = "AB12CDE",
            tests = listOf(
                testRecord(
                    dateTested = "10 May 2026",
                    advisories = listOf(
                        "Offside Front Brake pad wearing thin",
                        "Nearside Rear Tyre worn close to legal limit"
                    )
                )
            )
        )

        val report = ComponentHealthCalculator.calculate(history)

        val brakes = report.components.first { it.domain == ComponentHealthCalculator.ComponentDomain.BRAKES }
        val tyres = report.components.first { it.domain == ComponentHealthCalculator.ComponentDomain.TYRES_WHEELS }

        assertEquals(90, brakes.score) // 100 - 10
        assertEquals(1, brakes.advisoryCount)
        assertEquals(90, tyres.score)
        assertEquals(1, tyres.advisoryCount)
    }

    @Test
    fun severeFailuresAndRecurringFaultsReduceScoreToAmberOrRed() {
        val history = MotHistoryData(
            registration = "AB12CDE",
            tests = listOf(
                testRecord(
                    dateTested = "1 June 2026",
                    result = "FAIL",
                    failures = listOf(
                        "Offside Front Suspension spring fractured",
                        "Nearside Front Suspension arm pin or bush excessively worn"
                    ),
                    advisories = listOf("Offside Front Anti-roll bar linkage pin or bush worn")
                ),
                testRecord(
                    dateTested = "1 June 2025",
                    result = "FAIL",
                    failures = listOf("Offside Front Suspension spring fractured")
                )
            )
        )

        val report = ComponentHealthCalculator.calculate(history)

        val suspension = report.components.first { it.domain == ComponentHealthCalculator.ComponentDomain.SUSPENSION_STEERING }

        assertEquals(ComponentHealthCalculator.ComponentStatus.RED, suspension.status)
        assertEquals(3, suspension.failureCount)
        assertNotNull(suspension.latestNote)
    }
}
