package com.example.vehiclechecker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testScrapeVehicleDataWithRealReg() = runBlocking {
        val result = VehicleScraper.scrapeVehicleData("LC63XRO")
        println("Scrape Result:\n$result")
        assertNull("Error message should be null", result.errorMessage)
        assertTrue("Make should contain TOYOTA", result.make.contains("TOYOTA", ignoreCase = true))
        assertTrue("Colour should contain SILVER", result.colour.contains("SILVER", ignoreCase = true))
        assertTrue("FuelType should contain PETROL", result.fuelType.contains("PETROL", ignoreCase = true))
        assertTrue("TaxStatus should contain Tax", result.taxStatus.contains("Tax", ignoreCase = true))
    }
}
