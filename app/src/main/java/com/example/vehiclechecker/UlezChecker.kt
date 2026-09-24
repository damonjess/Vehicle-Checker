package com.example.vehiclechecker

import java.util.Locale

/**
 * ULEZ / Clean Air Zone compliance check based on fuel type and Euro emissions status.
 * Rules mirror London ULEZ (the strictest CAZ): petrol Euro 4+, diesel Euro 6+, electric exempt.
 */
object UlezChecker {

    data class Result(val compliant: Boolean?, val title: String, val detail: String)

    fun check(fuelType: String, euroStatus: String, firstRegistered: String): Result {
        val fuel = fuelType.uppercase(Locale.UK)
        val euro = Regex("(\\d+)").find(euroStatus)?.groupValues?.get(1)?.toIntOrNull()
        val regYear = Regex("\\d{4}").find(firstRegistered)?.value?.toIntOrNull()

        return when {
            fuel.contains("ELECTRIC") || fuel.contains("HYDROGEN") ->
                Result(true, "ULEZ Compliant", "Zero emission vehicles are exempt from ULEZ and Clean Air Zone charges.")

            fuel.contains("PETROL") -> when {
                euro != null && euro >= 4 -> Result(true, "ULEZ Compliant", "Petrol Euro $euro meets the Euro 4 ULEZ standard.")
                regYear != null && regYear >= 2006 -> Result(true, "ULEZ Compliant", "Petrol registered from 2006 generally meets the Euro 4 standard.")
                else -> Result(false, "May incur ULEZ charge", "Petrol vehicles below Euro 4 (typically pre-2006) pay the London ULEZ daily charge.")
            }

            fuel.contains("DIESEL") -> when {
                euro != null && euro >= 6 -> Result(true, "ULEZ Compliant", "Diesel Euro $euro meets the Euro 6 ULEZ standard.")
                regYear != null && regYear >= 2016 -> Result(true, "ULEZ Compliant", "Diesel registered from late 2015/2016 generally meets Euro 6.")
                else -> Result(false, "May incur ULEZ charge", "Diesel vehicles below Euro 6 (typically pre-2015) pay the London ULEZ daily charge.")
            }

            else -> Result(null, "ULEZ status unknown", "Check compliance on the TfL website using the vehicle's registration.")
        }
    }
}
