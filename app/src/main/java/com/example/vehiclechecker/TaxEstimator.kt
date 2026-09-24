package com.example.vehiclechecker

import java.util.Locale

/**
 * Estimates the 12-month UK road tax (VED) rate from the DVLA fields we already have.
 * Rates are the published 2025/26 figures; shown in-app as an estimate with a GOV.UK link note.
 */
object TaxEstimator {

    data class Estimate(val amountPounds: Int?, val band: String, val note: String)

    fun estimate(
        firstRegistered: String,
        yearOfManufacture: String,
        engineSize: String,
        fuelType: String,
        co2: String
    ): Estimate {
        val regYear = yearFrom(firstRegistered) ?: yearOfManufacture.toIntOrNull()
        val co2Value = Regex("\\d+").find(co2)?.value?.toIntOrNull()
        val engineCc = Regex("\\d+").find(engineSize)?.value?.toIntOrNull()
        val fuel = fuelType.uppercase(Locale.UK)

        val regMonth = monthFrom(firstRegistered)

        // Electric vehicles
        if (fuel.contains("ELECTRIC")) {
            return if (regYear != null && regYear >= 2017) {
                Estimate(195, "Standard rate", "EVs pay the standard rate from their second year")
            } else {
                Estimate(0, "Exempt", "Zero emission vehicle")
            }
        }

        // Registered before 1 March 2001: taxed by engine size
        if (regYear != null && (regYear < 2001 || (regYear == 2001 && regMonth != null && regMonth < 3))) {
            val rate = if ((engineCc ?: 0) <= 1549) 200 else 325
            val band = if ((engineCc ?: 0) <= 1549) "Up to 1549cc" else "Over 1549cc"
            return Estimate(rate, band, "Pre-2001 vehicles are taxed by engine size")
        }

        // Registered from 1 March 2001 to 30 March 2017: taxed by CO2 band
        if (regYear != null && (regYear < 2017 || (regYear == 2017 && regMonth != null && regMonth < 4))) {
            if (co2Value == null) return Estimate(null, "CO2 band", "CO2 figure unavailable")
            val (band, rate) = co2Band(co2Value)
            return Estimate(rate, band, "Based on CO2 band for 2001–2017 vehicles")
        }

        // Registered from 1 April 2017: flat standard rate
        if (regYear != null && regYear > 2017 || (regYear == 2017 && (regMonth ?: 4) >= 4)) {
            return Estimate(
                195,
                "Standard rate",
                "Flat rate for post-2017 cars (higher rate applies if list price was over £40k)"
            )
        }

        return Estimate(null, "Unknown", "Not enough vehicle data to estimate")
    }

    /** CO2 bands for cars registered March 2001 – March 2017 (12-month rates). */
    private fun co2Band(co2: Int): Pair<String, Int> = when {
        co2 <= 100 -> "A (up to 100)" to 0
        co2 <= 110 -> "B (101–110)" to 35
        co2 <= 120 -> "C (111–120)" to 55
        co2 <= 130 -> "D (121–130)" to 70
        co2 <= 140 -> "E (131–140)" to 95
        co2 <= 150 -> "F (141–150)" to 115
        co2 <= 165 -> "G (151–165)" to 155
        co2 <= 175 -> "H (166–175)" to 185
        co2 <= 185 -> "I (176–185)" to 225
        co2 <= 200 -> "J (186–200)" to 275
        co2 <= 225 -> "K (201–225)" to 340
        co2 <= 255 -> "L (226–255)" to 360
        else -> "M (over 255)" to 385
    }

    private fun yearFrom(text: String): Int? =
        Regex("\\d{4}").find(text)?.value?.toIntOrNull()

    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12
    )

    private fun monthFrom(text: String): Int? {
        val lower = text.lowercase(Locale.UK)
        return MONTHS.entries.firstOrNull { lower.contains(it.key) }?.value
    }
}
