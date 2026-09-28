package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the running-cost maths: the mileage trail from the MOT history drives the estimate,
 * with a documented fallback when there is no odometer data.
 */
class RunningCostCalculatorTest {

    @Test
    fun petrolEstimateUsesTheMotMileage() {
        val estimate = RunningCostCalculator.estimate(
            fuelType = "PETROL",
            engineSize = "1998",
            motAnnualMiles = 4813,
            taxPerYear = 185,
        )

        assertTrue(estimate.milesFromMot)
        assertEquals(4813, estimate.annualMiles)
        assertEquals(848, estimate.fuelCostPerYear)
        assertEquals(18, estimate.fuelPencePerMile)
        assertEquals(1033, estimate.totalPerYear)
    }

    @Test
    fun dieselIsCheaperPerYearThanPetrolForTheSameMileage() {
        val petrol = RunningCostCalculator.estimate("PETROL", "1998", 4813, 185)
        val diesel = RunningCostCalculator.estimate("DIESEL", "1998", 4813, 185)

        assertTrue(diesel.fuelCostPerYear < petrol.fuelCostPerYear)
        assertEquals(663, diesel.fuelCostPerYear)
    }

    @Test
    fun electricUsesAPerMileEstimate() {
        val estimate = RunningCostCalculator.estimate("ELECTRICITY", "", 4813, 195)

        assertEquals(5, estimate.fuelPencePerMile)
        assertEquals(241, estimate.fuelCostPerYear)
        assertEquals(436, estimate.totalPerYear)
    }

    @Test
    fun fallsBackToTheUkAverageWithoutMotMileage() {
        val estimate = RunningCostCalculator.estimate("PETROL", "1998", null, 185)

        assertFalse(estimate.milesFromMot)
        assertEquals(RunningCostCalculator.FALLBACK_ANNUAL_MILES, estimate.annualMiles)
        assertEquals(1304, estimate.fuelCostPerYear)
        assertTrue(estimate.assumption.contains("no MOT mileage"))
    }

    @Test
    fun totalStillMakesSenseWhenTaxIsUnknown() {
        val estimate = RunningCostCalculator.estimate("PETROL", "1598", 4813, null)

        assertEquals(estimate.fuelCostPerYear, estimate.totalPerYear)
    }

    @Test
    fun zeroMotMileageIsTreatedAsMissing() {
        val estimate = RunningCostCalculator.estimate("PETROL", "1598", 0, 185)

        assertFalse(estimate.milesFromMot)
        assertEquals(RunningCostCalculator.FALLBACK_ANNUAL_MILES, estimate.annualMiles)
    }
}
