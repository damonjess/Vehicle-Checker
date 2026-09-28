package com.example.vehiclechecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object MotHistoryScraper {

    /**
     * Loads the public GOV.UK "Check MOT history" page for a registration and parses it.
     *
     * The service sits behind Imperva bot protection that blocks plain HTTP clients, so the
     * page is rendered through an off-screen WebView ([MotWebFetcher]) which runs the
     * protection's JavaScript challenge, then the finished HTML is parsed with Jsoup.
     */
    suspend fun fetchMotHistory(context: Context, registration: String): MotHistoryData {
        val cleanReg = registration.replace(" ", "").uppercase().trim()

        val html = MotWebFetcher.fetchResultsHtml(context.applicationContext, cleanReg)
            ?: return MotHistoryData(
                registration = cleanReg,
                errorMessage = "Could not load MOT history — please try again.",
            )

        return withContext(Dispatchers.IO) {
            parseMotHtml(html, cleanReg)
        }
    }

    private fun parseMotHtml(html: String, cleanReg: String): MotHistoryData {
        try {
            val doc: Document = Jsoup.parse(html, "https://www.check-mot.service.gov.uk/")
            val header = doc.selectFirst("main")?.text() ?: doc.body().text()

            // Vehicle summary: dates, colour, fuel, MOT expiry
            val motValidUntil = Regex("(?i)MOT valid until\\s+([0-9]{1,2} \\w+ [0-9]{4})")
                .find(header)?.groupValues?.get(1) ?: ""
            val dateRegistered = Regex("(?i)Date registered\\s+([0-9]{1,2} \\w+ [0-9]{4})")
                .find(header)?.groupValues?.get(1) ?: ""
            val colour = Regex("(?i)Colour\\s+([A-Za-z ]+?)\\s+Fuel")
                .find(header)?.groupValues?.get(1)?.trim() ?: ""
            val fuelType = Regex("(?i)Fuel type\\s+([A-Za-z -]+?)\\s+Date registered")
                .find(header)?.groupValues?.get(1)?.trim() ?: ""

            // Header line "LC63 XRO TOYOTA RAV4" — plate + make/model
            var reg = cleanReg
            var make = ""
            var model = ""
            Regex("(?i)([A-Z]{1,3}[0-9]{1,4} ?[A-Z]{3})\\s+([A-Z][A-Za-z0-9 -]+)")
                .find(header.replace("\n", " "))?.let { m ->
                    reg = m.groupValues[1].replace(" ", "")
                    val parts = m.groupValues[2].trim().split(" ", limit = 2)
                    make = parts.getOrNull(0) ?: ""
                    model = parts.getOrNull(1) ?: ""
                }

            // Individual tests
            val tests = mutableListOf<MotTestRecord>()
            var testItems = doc.select("[data-test-id=test-history-item]")
            if (testItems.isEmpty()) {
                testItems = doc.select(
                    ".govuk-accordion__section, .mot-history-item, [id^=mot-history-item], [data-test-id^=test-history]",
                )
            }

            for (item in testItems) {
                val dateTested = item.selectFirst(".govuk-heading-s, .govuk-accordion__section-heading, [data-test-id=test-date]")?.text() ?: ""
                var result = item.selectFirst("[data-test-id=test-result]")?.text()
                    ?.uppercase()?.replace(" ", "") ?: ""
                if (result.isBlank()) {
                    val itemText = item.text().uppercase()
                    if (itemText.contains("PASSED") || itemText.contains("PASS")) result = "PASS"
                    else if (itemText.contains("FAILED") || itemText.contains("FAIL")) result = "FAIL"
                }

                var mileageText = item.selectFirst("[data-test-id=test-history-odometer]")?.text()?.trim() ?: ""
                if (mileageText.isBlank()) {
                    Regex("(?i)([0-9,]+\\s*miles)").find(item.text())?.let {
                        mileageText = it.value
                    }
                }

                val testNumber = item.selectFirst("[data-test-id=test-number]")?.text()?.trim() ?: ""
                val expiryDate = item.selectFirst("[data-test-id=expiry-date]")?.text()?.trim() ?: ""

                val advisories = item.select("li")
                    .map { it.text().trim() }
                    .filter {
                        it.isNotBlank() &&
                            !it.contains("What are advisories", true) &&
                            !it.contains("Advisories are given", true)
                    }

                if (dateTested.isNotBlank() || result.isNotBlank() || mileageText.isNotBlank()) {
                    tests.add(
                        MotTestRecord(
                            dateTested = dateTested,
                            result = result,
                            mileage = mileageText,
                            mileageMiles = parseMileage(mileageText),
                            testNumber = testNumber,
                            expiryDate = expiryDate,
                            advisories = advisories,
                        ),
                    )
                }
            }

            // Safety recalls section
            var recallStatus = RecallStatus.UNKNOWN
            var recallDetail = ""
            doc.selectFirst("#recalls-content")?.let { recallsEl ->
                val recallText = recallsEl.text().trim()
                val testId = recallsEl.selectFirst("[data-test-id]")?.attr("data-test-id") ?: ""
                when {
                    testId.contains("outstanding") ||
                        recallText.contains("outstanding recall", true) ||
                        recallText.contains("has not been fixed", true) -> {
                        recallStatus = RecallStatus.OUTSTANDING
                        recallDetail = recallText
                    }
                    testId.contains("complete") || testId.contains("recall-no") ||
                        recallText.contains("no recalls", true) ||
                        recallText.contains("has not been recalled", true) ||
                        recallText.contains("no outstanding", true) -> {
                        recallStatus = RecallStatus.NONE
                        recallDetail = recallText
                    }
                    else -> {
                        recallStatus = RecallStatus.UNKNOWN
                        recallDetail = recallText.ifBlank { "Recall information is not available for this vehicle." }
                    }
                }
            }

            // No records at all (e.g. brand new vehicle)
            if (tests.isEmpty() && motValidUntil.isBlank() && header.contains("no MOT", ignoreCase = true)) {
                return MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "No MOT records found for $cleanReg",
                )
            }

            // Compute mileage difference vs the previous test (page lists newest first)
            val withDiffs = tests.mapIndexed { index, test ->
                val prev = tests.getOrNull(index + 1)
                val diff = if (test.mileageMiles != null && prev?.mileageMiles != null) {
                    test.mileageMiles - prev.mileageMiles
                } else null
                test.copy(mileageDifference = diff)
            }

            return MotHistoryData(
                registration = reg,
                make = make,
                model = model,
                colour = colour,
                fuelType = fuelType,
                dateRegistered = dateRegistered,
                motValidUntil = motValidUntil,
                tests = withDiffs,
                recallStatus = recallStatus,
                recallDetail = recallDetail,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return MotHistoryData(
                registration = cleanReg,
                errorMessage = "Could not load MOT history: ${e.message ?: "unknown error"}",
            )
        }
    }

    /** "51,801 miles" -> 51801 */
    private fun parseMileage(text: String): Int? =
        text.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() }?.toIntOrNull()
}
