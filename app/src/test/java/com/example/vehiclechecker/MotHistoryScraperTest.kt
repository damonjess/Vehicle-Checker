package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /**
     * Real markup: every row sits inside the accordion panel, so the panel must never be read as
     * a row of its own. The panel heading used to be parsed as a test with the date
     * "MOT history , Check mileage recorded at test, expiry date, and test outcome , Show".
     */
    @Test
    fun accordionWrapperIsNotParsedAsATestRow() {
        val wrapped = """
            <html><body><main>
              <div class="dvsa-vrm" data-test-id="vehicle-registration">LC63 XRO</div>
              <h1 class="govuk-heading-xl" data-test-id="vehicle-make-model">TOYOTA RAV4</h1>
              <div class="govuk-accordion" data-module="govuk-accordion">
                <div class="govuk-accordion__section">
                  <div class="govuk-accordion__section-header">
                    <h2 class="govuk-accordion__section-heading">
                      <span class="govuk-accordion__section-button">MOT history</span>
                    </h2>
                    <div class="govuk-accordion__section-summary govuk-body">
                      Check mileage recorded at test, expiry date, and test outcome
                    </div>
                  </div>
                  <div class="govuk-accordion__section-content">
                    <div class="govuk-grid-row" data-test-id="test-history-item">
                      <div class="govuk-grid-column-one-third">
                        <span class="govuk-caption-m">Date tested</span>
                        <div class="govuk-heading-s">23 January 2026</div>
                        <div class="govuk-heading-xl" data-test-id="test-result">PASS</div>
                      </div>
                      <div class="govuk-grid-column-two-thirds">
                        <span class="govuk-caption-m">Mileage</span>
                        <div class="govuk-heading-s" data-test-id="test-history-odometer">51,801 miles</div>
                        <span class="govuk-caption-m">MOT test number</span>
                        <div class="govuk-heading-s" data-test-id="test-number">1234 5678 9012</div>
                      </div>
                    </div>
                    <div class="govuk-grid-row" data-test-id="test-history-item">
                      <div class="govuk-grid-column-one-third">
                        <span class="govuk-caption-m">Date tested</span>
                        <div class="govuk-heading-s">14 February 2025</div>
                        <div class="govuk-heading-xl" data-test-id="test-result">FAIL</div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </main></body></html>
        """.trimIndent()

        val result = MotHistoryScraper.parseMotHtml(wrapped, "LC63XRO")

        assertNull(result.errorMessage)
        assertEquals(2, result.tests.size)
        assertEquals("23 January 2026", result.tests[0].dateTested)
        assertEquals("14 February 2025", result.tests[1].dateTested)
    }

    /**
     * The live service splits defects by category heading, each followed by its own <ul>. A
     * failed test's advisories must not be recorded as failures, and dangerous defects must be
     * flagged as such. The "What are defects and advisories?" guidance is boilerplate.
     */
    @Test
    fun defectsAreSplitByTheirCategoryHeadings() {
        val failed = """
            <html><body><main>
              <div class="dvsa-vrm" data-test-id="vehicle-registration">KN66 PZG</div>
              <div class="govuk-accordion__section">
                <div class="govuk-accordion__section-content">
                  <div class="govuk-grid-row" data-test-id="test-history-item">
                    <div class="govuk-grid-column-one-third">
                      <div class="govuk-heading-s">3 October 2025</div>
                      <div class="govuk-heading-xl" data-test-id="test-result">FAIL</div>
                    </div>
                    <div class="govuk-grid-column-one-half">
                      <div class="govuk-heading-s" data-test-id="test-history-odometer">44,120 miles</div>
                      <div class="govuk-heading-s" data-test-id="test-number">4939 7234 1329</div>
                    </div>
                    <div class="govuk-grid-column-full" data-test-id="test-history-rfr-493972341329">
                      <span class="govuk-caption-m" data-test-id="dangerous-defect-items-heading">Do not drive until repaired (dangerous defects):</span>
                      <ul class="govuk-list govuk-list--bullet">
                        <li><span class="govuk-!-font-weight-bold">Offside Front Tyre has ply or cords exposed (5.2.3 (d) (ii))</span></li>
                      </ul>
                      <span class="govuk-caption-m" data-test-id="advisory-defect-comments-heading">Monitor and repair if necessary (advisories):</span>
                      <ul class="govuk-list govuk-list--bullet">
                        <li>Nearside Rear Tyre worn close to legal limit (5.2.3 (e))</li>
                        <li>Offside Rear Tyre worn close to legal limit (5.2.3 (e))</li>
                      </ul>
                      <details class="govuk-details" data-test-id="defect-category-definition-container">
                        <summary class="govuk-details__summary">What are defects and advisories?</summary>
                        <div class="govuk-details__text"><ul><li>Guidance that must not be parsed</li></ul></div>
                      </details>
                    </div>
                  </div>
                </div>
              </div>
            </main></body></html>
        """.trimIndent()

        val result = MotHistoryScraper.parseMotHtml(failed, "KN66PZG")

        assertNull(result.errorMessage)
        assertEquals(1, result.tests.size)
        val test = result.tests[0]
        assertEquals("FAIL", test.result)
        assertEquals(
            listOf("[DANGEROUS] Offside Front Tyre has ply or cords exposed (5.2.3 (d) (ii))"),
            test.failures,
        )
        assertEquals(
            listOf(
                "Nearside Rear Tyre worn close to legal limit (5.2.3 (e))",
                "Offside Rear Tyre worn close to legal limit (5.2.3 (e))",
            ),
            test.advisories,
        )
    }

    /** Too-new vehicle: an answer in its own right, not a fetch failure to retry. */
    @Test
    fun vehicleThatHasNotHadItsFirstTestIsReportedPlainly() {
        val notYetTested = """
            <html><body><main>
              <div class="dvsa-vrm" data-test-id="vehicle-registration">FV25 OGZ</div>
              <h1 class="govuk-heading-xl" data-test-id="vehicle-make-model">CFMOTO 450NK</h1>
              <div class="govuk-accordion" data-module="govuk-accordion">
                <div class="govuk-accordion__section">
                  <div class="govuk-accordion__section-header">
                    <h2 class="govuk-accordion__section-heading">
                      <span class="govuk-accordion__section-button">MOT history</span>
                    </h2>
                    <div class="govuk-accordion__section-summary govuk-body">
                      Check mileage recorded at test, expiry date, and test outcome
                    </div>
                  </div>
                  <div class="govuk-accordion__section-content">
                    <div class="govuk-inset-text" data-test-id="vehicle-not-had-first-test">
                      This vehicle hasn't had its first MOT.
                    </div>
                  </div>
                </div>
              </div>
            </main></body></html>
        """.trimIndent()

        val result = MotHistoryScraper.parseMotHtml(notYetTested, "FV25OGZ")

        assertEquals("FV25OGZ", result.registration)
        assertTrue(result.errorMessage?.contains("hasn't had its first MOT") == true)
        assertTrue(result.tests.isEmpty())
        assertTrue(!MotHistoryScraper.isUsable(result))
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

    /**
     * End-to-end parse of a whole results page captured from the live service: accordion
     * wrapper, garage forms, guidance details, a failed test carrying both dangerous defects
     * and advisories, and the recalls block. Everything the screen depends on must survive.
     */
    @Test
    fun fullLiveResultsPageParsesEndToEnd() {
        val html = javaClass.getResourceAsStream("/live_results.html")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("live_results.html missing from the test classpath", html)

        val result = MotHistoryScraper.parseMotHtml(html!!, "LC63XRO")

        assertNull(result.errorMessage)
        assertEquals("LC63XRO", result.registration)
        assertEquals("TOYOTA", result.make)
        assertEquals("RAV4", result.model)
        assertEquals("Silver", result.colour)
        assertEquals("Petrol", result.fuelType)
        assertEquals("31 January 2014", result.dateRegistered)
        assertEquals("13 February 2027", result.motValidUntil)
        assertEquals(RecallStatus.UNKNOWN, result.recallStatus)

        // The accordion panel wraps both rows and must not appear as a third "test"
        assertEquals(2, result.tests.size)

        val failed = result.tests[0]
        assertEquals("3 October 2025", failed.dateTested)
        assertEquals("FAIL", failed.result)
        assertEquals("44,120 miles", failed.mileage)
        assertEquals(44120, failed.mileageMiles)
        assertEquals("4939 7234 1329", failed.testNumber)
        assertEquals(
            listOf("[DANGEROUS] Offside Front Tyre has ply or cords exposed (5.2.3 (d) (ii))"),
            failed.failures,
        )
        assertEquals(
            listOf(
                "Nearside Rear Tyre worn close to legal limit (5.2.3 (e))",
                "Offside Rear Tyre worn close to legal limit (5.2.3 (e))",
            ),
            failed.advisories,
        )

        val passed = result.tests[1]
        assertEquals("23 January 2024", passed.dateTested)
        assertEquals("PASS", passed.result)
        assertEquals("13 February 2025", passed.expiryDate)
        assertEquals(listOf("Nearside front tyre worn close to legal limit"), passed.advisories)
        assertTrue(passed.failures.isEmpty())

        assertTrue(MotHistoryScraper.isUsable(result))
    }
}
