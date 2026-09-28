package com.example.vehiclechecker

import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAiAnalystTest {

    private val vehicle = VehicleData(
        registration = "HV62JVK",
        make = "BMW",
        yearOfManufacture = "2012",
        engineSize = "1995",
        fuelType = "DIESEL"
    )

    @Test
    fun testBuildPromptWithNullMotHistory() {
        val prompt = VehicleAiAnalyst.buildPrompt(vehicle, null)

        assertTrue(prompt.contains("2012 BMW"))
        assertTrue(prompt.contains("1995 DIESEL engine"))
        assertTrue(prompt.contains("No specific MOT test history recorded"))
    }

    @Test
    fun testBuildPromptWithMotHistoryFailuresAndAdvisories() {
        val motHistory = MotHistoryData(
            registration = "HV62JVK",
            make = "BMW",
            model = "3 SERIES",
            tests = listOf(
                MotTestRecord(
                    dateTested = "15 May 2023",
                    result = "PASS",
                    mileage = "51,800 miles",
                    advisories = listOf("Nearside front brake disc worn")
                ),
                MotTestRecord(
                    dateTested = "10 May 2022",
                    result = "FAIL",
                    mileage = "45,000 miles",
                    failures = listOf("[DANGEROUS] Subframe corrosion severely weakening structure")
                )
            )
        )

        val prompt = VehicleAiAnalyst.buildPrompt(vehicle, motHistory)

        assertTrue(prompt.contains("BMW 3 SERIES"))
        assertTrue(prompt.contains("Test #1: Date 15 May 2023 | Result: PASS | Mileage: 51,800 miles"))
        assertTrue(prompt.contains("Nearside front brake disc worn"))
        assertTrue(prompt.contains("Test #2: Date 10 May 2022 | Result: FAIL | Mileage: 45,000 miles"))
        assertTrue(prompt.contains("[DANGEROUS] Subframe corrosion severely weakening structure"))
    }

    @Test
    fun testBuildPromptIncludesRecallAndOdometerFlags() {
        val motHistory = MotHistoryData(
            registration = "HV62JVK",
            make = "BMW",
            model = "3 SERIES",
            recallStatus = RecallStatus.OUTSTANDING,
            recallDetail = "Airbag inflator defect",
            tests = listOf(
                MotTestRecord(dateTested = "15 May 2023", result = "PASS", mileage = "40,000 miles", mileageMiles = 40000),
                MotTestRecord(dateTested = "10 May 2022", result = "PASS", mileage = "50,000 miles", mileageMiles = 50000)
            )
        )

        val prompt = VehicleAiAnalyst.buildPrompt(vehicle, motHistory)

        assertTrue(prompt.contains("Outstanding safety recall recorded (Airbag inflator defect)"))
        assertTrue(prompt.contains("Mileage / Odometer Flags:"))
        assertTrue(prompt.contains("Mileage decreased between tests"))
    }
}
