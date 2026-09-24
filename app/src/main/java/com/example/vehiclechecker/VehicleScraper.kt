package com.example.vehiclechecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object VehicleScraper {

    private const val BASE_URL = "https://vehicleenquiry.service.gov.uk"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 15000

    private const val VRN_FIELD = "wizard_vehicle_enquiry_capture_vrn[vrn]"
    private const val CONFIRM_FIELD = "wizard_vehicle_enquiry_capture_confirm_vehicle[confirmed]"

    suspend fun scrapeVehicleData(registration: String, context: Context? = null): VehicleData {
        return withContext(Dispatchers.IO) {
            try {
                val cleanReg = registration.replace(" ", "").uppercase().trim()
                if (cleanReg.isEmpty()) {
                    return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.enter_number_plate)
                            ?: "Please enter a number plate."
                    )
                }

                // Step 1: GET the search page to pick up session cookies + CSRF token
                val initial = Jsoup.connect("$BASE_URL/?locale=en")
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .execute()

                val cookies = initial.cookies()
                val searchDoc = initial.parse()
                val searchForm = searchDoc.select("form")
                    .firstOrNull { it.select("input[name=\"$VRN_FIELD\"]").isNotEmpty() }

                val authenticityToken = searchForm
                    ?.selectFirst("input[name=authenticity_token]")
                    ?.attr("value")
                    ?: return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.error_message, "Could not reach the DVLA service")
                            ?: "Error: Could not reach the DVLA service"
                    )

                // Step 2: POST the registration -> confirmation page
                val confirmDoc = Jsoup.connect("$BASE_URL/vehicle-enquiry/save?locale=en")
                    .userAgent(USER_AGENT)
                    .cookies(cookies)
                    .data("authenticity_token", authenticityToken, VRN_FIELD, cleanReg)
                    .method(Connection.Method.POST)
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .parse()

                if (confirmDoc.location().contains("VehicleNotFound") ||
                    confirmDoc.body().text().contains("not found", ignoreCase = true)
                ) {
                    return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.no_data_found, cleanReg)
                            ?: "No data found for registration: $cleanReg"
                    )
                }

                // Confirmation page: registration, make and colour
                var registrationLabel = ""
                var make = ""
                var colour = ""
                for (row in confirmDoc.select(".govuk-summary-list__row")) {
                    val key = row.selectFirst("dt")?.text()?.lowercase() ?: continue
                    val value = row.selectFirst("dd")?.text() ?: ""
                    when {
                        key.contains("registration") -> registrationLabel = value
                        key.contains("make") -> make = value
                        key.contains("colour") || key.contains("color") -> colour = value
                    }
                }

                // Step 3: POST "Yes" to the confirmation -> full details page
                val confirmForm = confirmDoc.select("form")
                    .firstOrNull { it.select("input[type=radio][name=\"$CONFIRM_FIELD\"]").isNotEmpty() }
                    ?: return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.no_data_found, cleanReg)
                            ?: "No data found for registration: $cleanReg"
                    )

                val confirmToken = confirmForm.selectFirst("input[name=authenticity_token]")?.attr("value") ?: ""
                val confirmAction = confirmForm.absUrl("action").ifEmpty { "$BASE_URL/ConfirmVehicle?locale=en" }

                val resultDoc = Jsoup.connect(confirmAction)
                    .userAgent(USER_AGENT)
                    .cookies(cookies)
                    .data("authenticity_token", confirmToken, CONFIRM_FIELD, "Yes")
                    .method(Connection.Method.POST)
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .parse()

                return@withContext parseResultsPage(resultDoc, registrationLabel, make, colour, context)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext VehicleData(
                    errorMessage = context?.getString(R.string.error_message, e.message ?: "Unknown error")
                        ?: "Error: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun parseResultsPage(
        doc: Document,
        registrationLabel: String,
        make: String,
        colour: String,
        context: Context?
    ): VehicleData {
        var registration = registrationLabel
        var makeValue = make
        var colourValue = colour
        var fuelType = ""
        var yearOfManufacture = ""
        var firstRegistered = ""
        var engineSize = ""
        var co2Emissions = ""
        var vehicleStatus = ""
        var wheelplan = ""
        var lastV5cIssued = ""
        var taxDueDate = ""
        var motStatus = ""
        var motExpiryDate = ""

        // Vehicle details list (each row has a stable element id, fall back to dt text)
        for (row in doc.select(".govuk-summary-list__row")) {
            val key = row.selectFirst("dt")?.text()?.lowercase() ?: continue
            val value = row.selectFirst("dd")?.text() ?: ""
            when (row.id()) {
                "make" -> if (makeValue.isBlank()) makeValue = value
                "vehicle_colour" -> if (colourValue.isBlank()) colourValue = value
                "fuel_type" -> fuelType = value
                "year_of_manufacture" -> yearOfManufacture = value
                "date_of_first_registration" -> firstRegistered = value
                "engine_capacity" -> engineSize = value
                "co2_emissions" -> co2Emissions = value
                "vehicle_status" -> vehicleStatus = value
                "wheelplan" -> wheelplan = value
                "date_of_last_v5c_issued" -> lastV5cIssued = value
                else -> {
                    when {
                        key.contains("make") -> if (makeValue.isBlank()) makeValue = value
                        key.contains("colour") -> if (colourValue.isBlank()) colourValue = value
                        key.contains("fuel") -> fuelType = value
                        key.contains("year of manufacture") -> yearOfManufacture = value
                        key.contains("first registration") -> firstRegistered = value
                        key.contains("cylinder capacity") -> engineSize = value
                        key.contains("co2") -> co2Emissions = value
                        key.contains("vehicle status") -> vehicleStatus = value
                        key.contains("wheelplan") -> wheelplan = value
                        key.contains("v5c") -> lastV5cIssued = value
                    }
                }
            }
        }

        // Tax banner: green panel = taxed, red panel = untaxed/SORN
        doc.selectFirst("#tax-status-panel")?.let { panel ->
            val isUntaxed = panel.hasClass("govuk-panel--confirmation").not()
            val text = panel.text()
            when {
                isUntaxed && text.contains("SORN", ignoreCase = true) -> vehicleStatus = "SORN"
                isUntaxed -> vehicleStatus = "Untaxed"
                vehicleStatus.isBlank() -> vehicleStatus = "Taxed"
            }
            taxDueDate = TAX_DUE_REGEX.find(text)?.value ?: ""
        }

        // MOT banner: green panel = valid, red panel = expired/no MOT
        doc.selectFirst("#mot-status-panel")?.let { panel ->
            val isValid = panel.hasClass("govuk-panel--confirmation")
            val text = panel.text()
            motStatus = if (isValid) "Valid" else "Expired"
            motExpiryDate = EXPIRES_REGEX.find(text)?.value ?: ""
        }

        if (makeValue.isBlank() && vehicleStatus.isBlank()) {
            return VehicleData(
                errorMessage = context?.getString(R.string.no_data_found, registration)
                    ?: "No data found for registration: $registration"
            )
        }

        return VehicleData(
            registration = registration,
            make = makeValue,
            colour = colourValue,
            fuelType = fuelType,
            yearOfManufacture = yearOfManufacture,
            firstRegistered = firstRegistered,
            engineSize = engineSize,
            co2Emissions = co2Emissions,
            vehicleStatus = vehicleStatus,
            wheelplan = wheelplan,
            lastV5cIssued = lastV5cIssued,
            taxStatus = vehicleStatus,
            taxDueDate = taxDueDate,
            motStatus = motStatus,
            motExpiryDate = motExpiryDate
        )
    }

    private val TAX_DUE_REGEX = Regex("Tax due:\\s*\\d{1,2} \\w+ \\d{4}", RegexOption.IGNORE_CASE)
    private val EXPIRES_REGEX = Regex("Expires:\\s*\\d{1,2} \\w+ \\d{4}", RegexOption.IGNORE_CASE)
}
