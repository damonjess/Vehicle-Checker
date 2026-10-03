package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the rules the weekly background status refresh obeys: a bad scrape must never
 * destroy dates we already hold, and the offline cache is only replaced by a payload that
 * still identifies the vehicle.
 */
class StatusRefresherTest {

    private val storedTax = DateUtils.parseFullDate("1 April 2026")!!
    private val storedMot = DateUtils.parseFullDate("12 March 2027")!!

    private fun fresh(
        registration: String = "LC63XRO",
        make: String = "TOYOTA",
        taxDueDate: String = "",
        motExpiryDate: String = "",
        errorMessage: String? = null
    ) = VehicleData(
        registration = registration,
        make = make,
        taxStatus = "TAXED",
        taxDueDate = taxDueDate,
        motStatus = "Valid",
        motExpiryDate = motExpiryDate,
        errorMessage = errorMessage
    )

    @Test
    fun freshDatesReplaceTheStoredOnes() {
        val outcome = StatusRefresher.merge(
            existingTaxDueEpochMs = storedTax,
            existingMotExpiryEpochMs = storedMot,
            fresh = fresh(taxDueDate = "1 October 2026", motExpiryDate = "12 March 2028")
        )

        assertEquals(DateUtils.parseFullDate("1 October 2026"), outcome.taxDueEpochMs)
        assertEquals(DateUtils.parseFullDate("12 March 2028"), outcome.motExpiryEpochMs)
        assertNotNull(outcome.cacheableVehicle)
    }

    @Test
    fun failedScrapeKeepsTheStoredDatesAndTheCache() {
        val outcome = StatusRefresher.merge(
            existingTaxDueEpochMs = storedTax,
            existingMotExpiryEpochMs = storedMot,
            fresh = fresh(errorMessage = "Error: Could not reach the DVLA service")
        )

        assertEquals(storedTax, outcome.taxDueEpochMs)
        assertEquals(storedMot, outcome.motExpiryEpochMs)
        assertNull(outcome.cacheableVehicle)
    }

    @Test
    fun aDateMissingFromAFreshResponseFallsBackToTheStoredOne() {
        val outcome = StatusRefresher.merge(
            existingTaxDueEpochMs = storedTax,
            existingMotExpiryEpochMs = storedMot,
            fresh = fresh(taxDueDate = "", motExpiryDate = "12 March 2028")
        )

        assertEquals(storedTax, outcome.taxDueEpochMs)
        assertEquals(DateUtils.parseFullDate("12 March 2028"), outcome.motExpiryEpochMs)
    }

    @Test
    fun aResponseThatDoesNotNameTheVehicleNeverOverwritesTheCache() {
        val outcome = StatusRefresher.merge(
            existingTaxDueEpochMs = storedTax,
            existingMotExpiryEpochMs = storedMot,
            fresh = fresh(taxDueDate = "1 October 2026", make = "")
        )

        assertEquals(DateUtils.parseFullDate("1 October 2026"), outcome.taxDueEpochMs)
        assertNull(outcome.cacheableVehicle)
    }

    @Test
    fun datesStayNullWhenNeitherSideHasThem() {
        val outcome = StatusRefresher.merge(
            existingTaxDueEpochMs = null,
            existingMotExpiryEpochMs = null,
            fresh = fresh(taxDueDate = "not a date", motExpiryDate = "")
        )

        assertNull(outcome.taxDueEpochMs)
        assertNull(outcome.motExpiryEpochMs)
        assertNotNull(outcome.cacheableVehicle)
    }

    @Test
    fun aUsableResponseIsCarriedThroughToTheCache() {
        val response = fresh(taxDueDate = "1 April 2027", motExpiryDate = "12 March 2028")

        val outcome = StatusRefresher.merge(storedTax, storedMot, response)

        assertEquals(response, outcome.cacheableVehicle)
    }
}
