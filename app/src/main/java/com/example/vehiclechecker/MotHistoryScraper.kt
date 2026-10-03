package com.example.vehiclechecker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object MotHistoryScraper {

    /** Every real test row is headed by a date such as "23 January 2026". */
    private val DATE_LIKE = Regex("""\b\d{1,2} \w{3,9} \d{4}\b""")

    private const val FAILURE = "FAILURE"
    private const val ADVISORY = "ADVISORY"

    /**
     * Loads the public GOV.UK "Check MOT history" page for a registration and parses it.
     *
     * The service sits behind Imperva bot protection that blocks plain HTTP clients, so the
     * page is rendered through an off-screen WebView ([MotWebFetcher]) which runs the
     * protection's JavaScript challenge, then the finished HTML is parsed with Jsoup.
     */
    suspend fun fetchMotHistory(context: Context, registration: String): MotHistoryData {
        val cleanReg = registration.replace(" ", "").uppercase().trim()

        // Pass the caller's context through (not applicationContext): the fetcher attaches its
        // WebView to the activity window so Chromium runs the challenge JS unthrottled.
        val html = MotWebFetcher.fetchResultsHtml(context, cleanReg)
            ?: return MotHistoryData(
                registration = cleanReg,
                errorMessage = if (MotWebFetcher.lastFailure() == MotWebFetcher.FAILURE_BLOCKED) {
                    "The MOT site refused the automatic check — tap Retry, or Open in browser to view it."
                } else {
                    "MOT history didn't load (${MotWebFetcher.lastFailure() ?: "timeout"}) — tap Retry."
                },
            )

        return withContext(Dispatchers.IO) {
            parseMotHtml(html, cleanReg)
        }
    }

    /** Visible to unit tests so the not-found and zero-test answers stay locked in. */
    internal fun parseMotHtml(html: String, cleanReg: String): MotHistoryData {
        try {
            val doc: Document = Jsoup.parse(html, "https://www.check-mot.service.gov.uk/")

            // When DVSA has no record of the plate at all, the service redirects to its
            // registration-search page. That is an authoritative "no history" answer, so report
            // it plainly instead of blaming a fetch problem. A real results page always carries
            // the plate, so it can never be mistaken for the search page.
            val searchInput = doc.selectFirst(
                "input[name=registration], input#registration, form[action*=results]"
            )
            if (searchInput != null && doc.selectFirst("[data-test-id=vehicle-registration], .dvsa-vrm") == null) {
                return MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "No MOT history found for $cleanReg",
                )
            }

            // A vehicle too new to have been tested: the service renders the accordion shell
            // with this marker instead of any test rows. That is a real answer, not a fetch
            // failure — and the accordion wrapper must never be mistaken for a test row.
            if (doc.selectFirst("[data-test-id=vehicle-not-had-first-test]") != null) {
                return MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "This vehicle hasn't had its first MOT yet — there are no test records to show.",
                )
            }

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

            // Vehicle identity: the page renders the plate in a .dvsa-vrm div and the
            // make/model in an h1.govuk-heading-xl (e.g. "TOYOTA RAV4"). Fall back to a
            // plain-text scan of the header for older page variants.
            var reg = cleanReg
            var make = ""
            var model = ""
            doc.selectFirst(".dvsa-vrm")?.text()?.trim()?.let { vrm ->
                if (vrm.isNotBlank()) reg = vrm.replace(" ", "").uppercase()
            }
            val headingText = doc.selectFirst("h1.govuk-heading-xl, h1")?.text()?.trim().orEmpty()
            if (headingText.isNotBlank() && !headingText.contains(cleanReg, ignoreCase = true)) {
                val parts = headingText.split(" ", limit = 2)
                make = parts.getOrNull(0) ?: ""
                model = parts.getOrNull(1) ?: ""
            } else {
                Regex("(?i)([A-Z]{1,3}[0-9]{1,4} ?[A-Z]{3})\\s+([A-Z][A-Za-z0-9 -]+)")
                    .find(header.replace("\n", " "))?.let { m ->
                        reg = m.groupValues[1].replace(" ", "")
                        val parts = m.groupValues[2].trim().split(" ", limit = 2)
                        make = parts.getOrNull(0) ?: ""
                        model = parts.getOrNull(1) ?: ""
                    }
            }

            // Individual tests. Never select `.govuk-accordion__section` here: that is the panel
            // wrapping the whole list, and it used to be parsed as one fake test row whose
            // "date" was the panel heading ("MOT history , Check mileage recorded at test...").
            val tests = mutableListOf<MotTestRecord>()
            var testItems: List<Element> = doc.select("[data-test-id=test-history-item]")
            if (testItems.isEmpty()) {
                testItems = doc
                    .select(".mot-history-item, [id^=mot-history-item], [data-test-id^=test-history]")
                    .filter { it.attr("data-test-id") != "test-history-item" && it.selectFirst("[data-test-id=test-result]") != null }
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

                val isPass = result.equals("PASS", ignoreCase = true)
                val failures = mutableListOf<String>()
                val advisories = mutableListOf<String>()

                // Guard against a non-test block sneaking through the selectors: every real row
                // carries a date like "23 January 2026".
                if (!DATE_LIKE.containsMatchIn(dateTested)) {
                    safeLogD("MotScraper", "Skipping '$dateTested' — not a test date")
                    continue
                }

                // Preferred path: the live service groups defects under a category heading span
                // ([data-test-id=...-heading]) followed by a <ul> of items for that category.
                extractStructuredDefects(item, failures, advisories)

                val failElements = item.select("[data-test-id=fail-item], [data-test-id=failure-item], [data-test-id=defect-item], .defect-item, .fail-item")
                val advisoryElements = item.select("[data-test-id=advisory-item], .advisory-item")

                if (failures.isNotEmpty() || advisories.isNotEmpty()) {
                    // Structured parse already produced the lists.
                } else if (failElements.isNotEmpty() || advisoryElements.isNotEmpty()) {
                    failElements.forEach { el ->
                        val text = el.text().trim()
                        if (text.isNotBlank() && !text.contains("What are", true)) failures.add(text)
                    }
                    advisoryElements.forEach { el ->
                        val text = el.text().trim()
                        if (text.isNotBlank() && !text.contains("What are", true)) advisories.add(text)
                    }
                } else {
                    var currentSection = if (!isPass) "FAILURE" else "ADVISORY"
                    val blockElements = item.select("h3, h4, h5, p, div.govuk-heading-s, li")
                    for (el in blockElements) {
                        val text = el.text().trim()
                        if (text.isBlank() || text.contains("What are advisories", true) || text.contains("Advisories are given", true)) continue

                        val upper = text.uppercase()
                        if (upper.contains("FAILURE") || upper.contains("MAJOR DEFECT") || upper.contains("DANGEROUS DEFECT")) {
                            currentSection = "FAILURE"
                            continue
                        } else if (upper.contains("ADVISOR") || upper.contains("MONITOR AND REPAIR")) {
                            currentSection = "ADVISORY"
                            continue
                        } else if (upper.contains("MINOR DEFECT")) {
                            currentSection = "ADVISORY"
                            continue
                        }

                        if (el.tagName() == "li" || el.hasClass("govuk-list--bullet")) {
                            if (currentSection == "FAILURE") {
                                if (!failures.contains(text)) failures.add(text)
                            } else {
                                if (!advisories.contains(text)) advisories.add(text)
                            }
                        }
                    }

                    if (failures.isEmpty() && advisories.isEmpty()) {
                        val allLi = item.select("li")
                            .map { it.text().trim() }
                            .filter {
                                it.isNotBlank() &&
                                    !it.contains("What are advisories", true) &&
                                    !it.contains("Advisories are given", true)
                            }
                        if (isPass) {
                            advisories.addAll(allLi)
                        } else {
                            failures.addAll(allLi)
                        }
                    }
                }

                safeLogD("MotScraper", "Parsed test on $dateTested: Result=$result, Failures=${failures.size}, Advisories=${advisories.size}")

                if (dateTested.isNotBlank() && result.isNotBlank()) {
                    tests.add(
                        MotTestRecord(
                            dateTested = dateTested,
                            result = result,
                            mileage = mileageText,
                            mileageMiles = parseMileage(mileageText),
                            testNumber = testNumber,
                            expiryDate = expiryDate,
                            advisories = advisories,
                            failures = failures,
                        ),
                    )
                }
            }

            // Safety recalls section
            var recallStatus = RecallStatus.UNKNOWN
            var recallDetail = ""
            val recallsEl = doc.selectFirst("#recalls-content")
                ?: doc.selectFirst("[data-test-id*='recall']")
                ?: doc.selectFirst(".recalls-status")
                ?: doc.select("p:contains(recall), div:contains(recall)").firstOrNull()

            recallsEl?.let { el ->
                val recallText = el.text().trim()
                val testId = el.selectFirst("[data-test-id]")?.attr("data-test-id") ?: ""
                when {
                    testId.contains("outstanding") ||
                        recallText.contains("outstanding recall", true) ||
                        recallText.contains("has not been fixed", true) ||
                        recallText.contains("unfixed recall", true) -> {
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
            if (tests.isEmpty() && motValidUntil.isBlank() &&
                Regex("no MOT|no test|no record|not found", RegexOption.IGNORE_CASE).containsMatchIn(header)
            ) {
                return MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "No MOT records found for $cleanReg",
                )
            }

            // A results page that rendered but produced no usable rows must NOT be cached
            // as a valid, empty history — that poisons the offline cache and blanks every
            // chart on later launches. Surface it as an error instead.
            if (tests.isEmpty()) {
                return MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "MOT history loaded but no test records were found for $cleanReg — please try again.",
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

    /**
     * Reads defects straight from the markup the live service renders: inside a
     * `[data-test-id=test-history-rfr-*]` block, each category heading
     * (`[data-test-id=*-heading]`) is immediately followed by a `<ul>` of that category's items.
     *
     * Splitting on those headings is what keeps a failed test's advisories out of its failure
     * list — every `<li>` on a failed test used to be recorded as a failure.
     */
    private fun extractStructuredDefects(
        item: Element,
        failures: MutableList<String>,
        advisories: MutableList<String>,
    ) {
        var section: String? = null
        var dangerous = false
        for (el in item.select("[data-test-id\$=heading], ul")) {
            // "What are defects and advisories?" guidance lives in a <details> inside the same
            // block; its text is boilerplate, never a vehicle's own defect.
            if (el.parents().any { it.tagName() == "details" }) continue

            val id = el.attr("data-test-id")
            if (id.endsWith("heading")) {
                val heading = el.text().lowercase()
                dangerous = id.contains("dangerous") || heading.contains("dangerous")
                section = when {
                    id.contains("failure") || id.contains("major") || id.contains("dangerous") ||
                        heading.contains("fail") || heading.contains("dangerous") ||
                        heading.contains("major") || heading.contains("do not drive") ||
                        heading.contains("repair immediately") -> FAILURE
                    else -> ADVISORY
                }
            } else if (el.tagName() == "ul" && section != null) {
                val target = if (section == FAILURE) failures else advisories
                for (li in el.select("li")) {
                    val text = li.text().trim()
                    if (text.isBlank() || text.contains("What are", true)) continue
                    val labelled = if (dangerous && section == FAILURE) "[DANGEROUS] $text" else text
                    if (!target.contains(labelled)) target.add(labelled)
                }
            }
        }
    }

    /** True when this is a live (non-error) result that carries no test rows — i.e. data we must not trust or cache. */
    fun isUsable(mot: MotHistoryData?): Boolean = mot != null && mot.errorMessage == null && mot.tests.isNotEmpty()

    /** "51,801 miles" -> 51801 */
    private fun parseMileage(text: String): Int? =
        text.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            // Ignored during local JVM unit tests
        }
    }
}
