package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below mirror the markup the live GOV.UK service actually renders (verified against
 * the real page), so a selector regression shows up here instead of on the phone.
 */
class MotHistoryScraperTest {

    private val searchPage = """
        <html><body><main>
          <div class="govuk-error-summary" data-module="govuk-error-summary">
            <h2 class="govuk-error-summary__title">There is a problem</h2>
            <ul class="govuk-list govuk-error-summary__list">
              <li><a href="#registration">Enter the registration number of the vehicle</a></li>
            </ul>
          </div>
          <h1 class="govuk-heading-xl">What is the vehicle's registration number?</h1>
          <form action="/results" method="get" novalidate>
            <div class="govuk-form-group">
              <label class="govuk-label" for="registration">Enter the registration number</label>
              <input class="govuk-input" id="registration" name="registration" type="text">
            </div>
            <button class="govuk-button" type="submit">Continue</button>
          </form>
        </main></body></html>
    """.trimIndent()

    private val resultsPage = """
        <html><body><main>
          <div class="dvsa-vrm" data-test-id="vehicle-registration">LC63 XRO</div>
          <h1 class="govuk-heading-xl" data-test-id="vehicle-make-model">TOYOTA RAV4</h1>
          <div data-test-id="colour-fuel-date-details">
            <p>Colour Silver Fuel type Petrol Date registered 31 January 2014</p>
          </div>
          <p>MOT valid until 13 February 2027</p>
          <div class="govuk-accordion__section" data-test-id="test-history-item">
            <h3 class="govuk-heading-s">23 January 2026</h3>
            <div data-test-id="test-result">PASS</div>
            <div data-test-id="expiry-date">13 February 2027</div>
            <div data-test-id="test-history-odometer">29,864 miles</div>
            <div data-test-id="test-number">1234 5678 9012</div>
            <ul><li>Nearside front tyre worn close to legal limit</li></ul>
          </div>
          <div class="govuk-accordion__section" data-test-id="test-history-item">
            <h3 class="govuk-heading-s">14 February 2025</h3>
            <div data-test-id="test-result">FAIL</div>
            <div data-test-id="test-history-odometer">23,762 miles</div>
            <ul><li>Offside headlamp aim too low</li></ul>
          </div>
        </main></body></html>
    """.trimIndent()

    /** The service bounces an unknown plate to this page — an answer, not a fetch failure. */
    @Test
    fun searchPageIsReportedAsNoHistory() {
        val result = MotHistoryScraper.parseMotHtml(searchPage, "ZZ99ZZZ")
        assertEquals("No MOT history found for ZZ99ZZZ", result.errorMessage)
        assertTrue(result.tests.isEmpty())
    }

    @Test
    fun renderedResultsPageIsParsed() {
        val result = MotHistoryScraper.parseMotHtml(resultsPage, "LC63XRO")

        assertNull(result.errorMessage)
        assertEquals("LC63XRO", result.registration)
        assertEquals("TOYOTA", result.make)
        assertEquals("RAV4", result.model)
        assertEquals("Silver", result.colour)
        assertEquals("Petrol", result.fuelType)
        assertEquals("31 January 2014", result.dateRegistered)
        assertEquals("13 February 2027", result.motValidUntil)
        assertEquals(2, result.tests.size)

        val newest = result.tests[0]
        assertEquals("23 January 2026", newest.dateTested)
        assertEquals("PASS", newest.result)
        assertEquals("29,864 miles", newest.mileage)
        assertEquals(29864, newest.mileageMiles)
        assertEquals(6102, newest.mileageDifference)
        assertEquals("13 February 2027", newest.expiryDate)
        assertEquals("1234 5678 9012", newest.testNumber)
        assertEquals(listOf("Nearside front tyre worn close to legal limit"), newest.advisories)

        val older = result.tests[1]
        assertEquals("FAIL", older.result)
        assertEquals(23762, older.mileageMiles)
        assertNull(older.mileageDifference)
    }

    /** A rendered page with no test rows must not be cached as a valid, empty history. */
    @Test
    fun resultsPageWithoutRowsIsAnError() {
        val emptyResults = """
            <html><body><main>
              <div class="dvsa-vrm" data-test-id="vehicle-registration">LC63 XRO</div>
              <h1 class="govuk-heading-xl">TOYOTA RAV4</h1>
            </main></body></html>
        """.trimIndent()
        val result = MotHistoryScraper.parseMotHtml(emptyResults, "LC63XRO")
        assertTrue(result.errorMessage?.contains("no test records") == true)
        assertTrue(!MotHistoryScraper.isUsable(result))
    }
}
