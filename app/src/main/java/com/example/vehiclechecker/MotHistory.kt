package com.example.vehiclechecker

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

data class MotHistoryData(
    val registration: String = "",
    val make: String = "",
    val model: String = "",
    val colour: String = "",
    val fuelType: String = "",
    val dateRegistered: String = "",
    val motValidUntil: String = "",
    val tests: List<MotTestRecord> = emptyList(),
    val recallStatus: RecallStatus = RecallStatus.UNKNOWN,
    val recallDetail: String = "",
    val errorMessage: String? = null
) {
    val passCount: Int get() = tests.count { it.isPass && it.advisories.isEmpty() }
    val passWithAdvisoriesCount: Int get() = tests.count { it.isPass && it.advisories.isNotEmpty() }
    val failCount: Int get() = tests.count { !it.isPass }
    val passRatePercent: Int
        get() = if (tests.isEmpty()) 0 else ((passCount + passWithAdvisoriesCount) * 100.0 / tests.size).roundToLong().toInt()

    /** Tests in consecutive years with no test recorded in between (a sign the vehicle was off the road). */
    val gapYears: Int
        get() {
            val years = tests.mapNotNull { it.yearTested }.distinct().sorted()
            if (years.size < 2) return 0
            var gaps = 0
            for (i in 1 until years.size) {
                if (years[i] - years[i - 1] > 1) gaps += years[i] - years[i - 1] - 1
            }
            return gaps
        }

    /** Latest recorded odometer reading, in miles. */
    val lastMileageMiles: Int? get() = tests.firstNotNullOfOrNull { it.mileageMiles }

    /**
     * Checks the odometer history (newest first) for signs of possible mileage
     * tampering: any reading lower than an earlier one, or huge unexplained jumps.
     */
    val mileageAnomalies: List<MileageAnomaly>
        get() {
            val anomalies = mutableListOf<MileageAnomaly>()
            val readings = tests.filter { it.mileageMiles != null }
            if (readings.size < 2) return anomalies

            // 1. Odometer went DOWN between tests — the classic clocking signal
            readings.forEachIndexed { index, test ->
                val later = readings.getOrNull(index + 1) ?: return@forEachIndexed
                val drop = later.mileageMiles!! - test.mileageMiles!!
                if (drop > 0) {
                    anomalies += MileageAnomaly(
                        title = "Mileage decreased between tests",
                        detail = "Odometer read ${String.format(Locale.UK, "%,d", test.mileageMiles!!)} miles " +
                            "on ${test.dateTested}, but an earlier test on ${later.dateTested} " +
                            "recorded ${String.format(Locale.UK, "%,d", later.mileageMiles!!)} miles — " +
                            "$drop miles higher. This can indicate mileage tampering."
                    )
                }
            }

            // 2. Unrealistic annual mileage (> 50,000 miles/year between tests)
            readings.forEachIndexed { index, test ->
                val later = readings.getOrNull(index + 1) ?: return@forEachIndexed
                val years = later.yearTested?.let { laterYear -> test.yearTested?.let { it - laterYear } } ?: return@forEachIndexed
                val miles = test.mileageMiles!! - later.mileageMiles!!
                if (years >= 1 && miles / years > 50_000) {
                    anomalies += MileageAnomaly(
                        title = "Very high mileage between tests",
                        detail = "${String.format(Locale.UK, "%,d", miles)} miles recorded between " +
                            "${later.dateTested} and ${test.dateTested} — roughly " +
                            "${String.format(Locale.UK, "%,d", miles / years)} miles per year, " +
                            "far above typical usage. Worth verifying the history."
                    )
                }
            }

            // 3. Mileage stopped being recorded after being present (rare, but a flag)
            val missingReadings = tests.count { it.mileageMiles == null }
            if (missingReadings > 0 && readings.isNotEmpty()) {
                anomalies += MileageAnomaly(
                    title = "Some tests have no mileage recorded",
                    detail = "$missingReadings of ${tests.size} tests are missing an odometer reading. " +
                        "Gaps in the mileage trail can make the vehicle's true usage harder to verify."
                )
            }

            return anomalies
        }

    /** Average miles per year based on first and last odometer readings. */
    val averageMilesPerYear: Int?
        get() {
            val readings = tests.filter { it.mileageMiles != null && it.yearTested != null }
            if (readings.size < 2) return null
            val first = readings.last()
            val last = readings.first()
            val years = max(1, (last.yearTested ?: 0) - (first.yearTested ?: 0))
            val miles = (last.mileageMiles ?: 0) - (first.mileageMiles ?: 0)
            if (miles <= 0) return null
            return (miles.toDouble() / years).roundToLong().toInt()
        }
}

data class MotTestRecord(
    val dateTested: String = "",
    val result: String = "",          // PASS / FAIL
    val mileage: String = "",         // e.g. "51,801 miles"
    val mileageMiles: Int? = null,    // numeric reading for charts/differences
    val mileageDifference: Int? = null, // miles since previous test (null for oldest test)
    val testNumber: String = "",
    val expiryDate: String = "",
    val advisories: List<String> = emptyList()
) {
    val isPass: Boolean get() = result.equals("PASS", ignoreCase = true)
    val yearTested: Int?
        get() = dateTested.trim().takeLast(4).toIntOrNull()

    val mileageDifferenceText: String?
        get() {
            val diff = mileageDifference ?: return null
            val sign = if (diff >= 0) "+" else "−"
            return "$sign${String.format(Locale.UK, "%,d", kotlin.math.abs(diff))} miles"
        }
}

/**
 * Flags that suggest the recorded mileage may have been tampered with ("clocked"),
 * shown as a warning banner in the Mileage Data section.
 */
data class MileageAnomaly(
    val title: String,
    val detail: String
)

enum class RecallStatus { NONE, OUTSTANDING, UNKNOWN }
