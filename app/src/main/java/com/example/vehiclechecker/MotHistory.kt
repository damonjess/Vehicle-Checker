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
