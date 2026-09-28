package com.example.vehiclechecker

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Estimates what the car costs to run for a year: fuel plus road tax.
 *
 * Everything here is derived from data the app already has for free — the mileage trail from the
 * DVSA MOT history gives a real annual mileage figure, and the DVLA record gives the fuel type and
 * engine size. Fuel prices and economy are typical UK averages rather than quotes, so the result is
 * always presented with the assumptions spelled out.
 */
object RunningCostCalculator {

    /** Used when the MOT trail has no usable mileage, so the feature still says something useful. */
    const val FALLBACK_ANNUAL_MILES = 7_400

    // Typical UK forecourt averages (pence per litre).
    private const val PETROL_PENCE_PER_LITRE = 139.5
    private const val DIESEL_PENCE_PER_LITRE = 145.5

    /** A mile of electric driving on typical home charging. */
    private const val ELECTRIC_PENCE_PER_MILE = 5.0

    private const val LITRES_PER_GALLON = 4.54609

    data class Estimate(
        val annualMiles: Int,
        /** True when the miles came from the MOT odometer trail rather than the UK average. */
        val milesFromMot: Boolean,
        val fuelCostPerYear: Int,
        val taxPerYear: Int?,
        val totalPerYear: Int,
        /** Rounded pence per mile, handy as a single headline number. */
        val fuelPencePerMile: Int,
        /** Plain-English basis for the figure, shown under the number in the UI. */
        val assumption: String,
    )

    fun estimate(
        fuelType: String,
        engineSize: String,
        motAnnualMiles: Int?,
        taxPerYear: Int?,
    ): Estimate {
        val motMiles = motAnnualMiles?.takeIf { it > 0 }
        val annualMiles = motMiles ?: FALLBACK_ANNUAL_MILES
        val fuel = fuelType.uppercase(Locale.UK)
        val cc = Regex("\\d+").find(engineSize)?.value?.toIntOrNull()

        val electric = fuel.contains("ELECTRICITY") || (fuel.contains("ELECTRIC") && !fuel.contains("HYBRID"))

        val pencePerMile: Double
        val economyNote: String
        if (electric) {
            pencePerMile = ELECTRIC_PENCE_PER_MILE
            economyNote = "an electric car charged at home"
        } else {
            val mpg = when {
                fuel.contains("HYBRID") -> if ((cc ?: 0) in 1..1600) 60 else 50
                fuel.contains("DIESEL") -> when {
                    cc == null -> 48
                    cc <= 1600 -> 58
                    cc <= 2000 -> 48
                    else -> 40
                }
                else -> when {
                    cc == null -> 38
                    cc <= 1200 -> 48
                    cc <= 1600 -> 42
                    cc <= 2000 -> 36
                    else -> 30
                }
            }
            val pricePerLitre = if (fuel.contains("DIESEL")) DIESEL_PENCE_PER_LITRE else PETROL_PENCE_PER_LITRE
            pencePerMile = (pricePerLitre / mpg) * LITRES_PER_GALLON
            val fuelName = if (fuel.contains("DIESEL")) "diesel" else "petrol"
            economyNote = "a $cc cc $fuelName at roughly $mpg mpg"
        }

        val fuelCostPerYear = (pencePerMile * annualMiles / 100).roundToInt()

        val milesText = String.format(Locale.UK, "%,d", annualMiles)
        val milesBasis = if (motMiles != null) {
            "the MOT mileage trail ($milesText miles/year)"
        } else {
            "the UK average of $milesText miles/year — no MOT mileage on record"
        }

        return Estimate(
            annualMiles = annualMiles,
            milesFromMot = motMiles != null,
            fuelCostPerYear = fuelCostPerYear,
            taxPerYear = taxPerYear,
            totalPerYear = fuelCostPerYear + (taxPerYear ?: 0),
            fuelPencePerMile = pencePerMile.roundToInt(),
            assumption = "Based on $milesBasis and $economyNote. " +
                "Fuel prices are UK averages — your real cost will differ.",
        )
    }
}
