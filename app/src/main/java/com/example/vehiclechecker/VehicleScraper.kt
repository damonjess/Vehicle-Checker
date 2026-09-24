package com.example.vehiclechecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup

object VehicleScraper {

    suspend fun scrapeVehicleData(registration: String, context: Context? = null): VehicleData {
        return withContext(Dispatchers.IO) {
            try {
                val cleanReg = registration.replace(" ", "").uppercase().trim()
                if (cleanReg.isEmpty()) {
                    return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.enter_number_plate) ?: "Please enter a number plate."
                    )
                }

                // Step 1: GET initial GOV.UK Vehicle Enquiry Service page
                val initialUrl = "https://vehicleenquiry.service.gov.uk/"
                val initialResponse = Jsoup.connect(initialUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .execute()

                var cookies = initialResponse.cookies()
                var doc = initialResponse.parse()

                // Find search form containing VRN input
                val searchForm = doc.select("form").find { it.select("input[name*=vrn]").isNotEmpty() }
                    ?: doc.select("form").last()
                val searchAction = searchForm?.absUrl("action") ?: "https://vehicleenquiry.service.gov.uk/vehicle-enquiry/save?locale=en"

                val searchInputs = searchForm?.select("input") ?: emptyList()
                val searchData = mutableMapOf<String, String>()
                for (input in searchInputs) {
                    val name = input.attr("name")
                    val value = input.attr("value")
                    if (name.isNotEmpty()) {
                        searchData[name] = value
                    }
                }
                val vrnKey = searchData.keys.firstOrNull { it.contains("vrn") }
                    ?: "wizard_vehicle_enquiry_capture_vrn[vrn]"
                searchData[vrnKey] = cleanReg

                // Step 2: POST registration to retrieve vehicle confirmation page
                val confirmResponse = Jsoup.connect(searchAction)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .cookies(cookies)
                    .data(searchData)
                    .method(Connection.Method.POST)
                    .timeout(10000)
                    .execute()

                cookies = (cookies + confirmResponse.cookies()).toMutableMap()
                doc = confirmResponse.parse()

                var make = ""
                var colour = ""
                var fuelType = ""
                var year = ""
                var taxStatus = ""
                var motStatus = ""

                // Extract summary data from step 2 (confirmation page)
                val confirmKeys = doc.select(".govuk-summary-list__key, dt, .summary-item")
                val confirmValues = doc.select(".govuk-summary-list__value, dd, .summary-value")
                for (i in 0 until minOf(confirmKeys.size, confirmValues.size)) {
                    val key = confirmKeys[i].text().lowercase()
                    val value = confirmValues[i].text()
                    if (key.contains("make")) make = value
                    if (key.contains("colour") || key.contains("color")) colour = value
                }

                // Step 3: POST confirmation choice ("Yes") to retrieve full Tax & MOT status
                val confirmForm = doc.select("form").find { it.select("input[type=radio]").isNotEmpty() }
                if (confirmForm != null) {
                    val confirmAction = confirmForm.absUrl("action").ifEmpty { searchAction }
                    val confirmInputs = confirmForm.select("input")
                    val confirmData = mutableMapOf<String, String>()
                    for (input in confirmInputs) {
                        val name = input.attr("name")
                        val value = input.attr("value")
                        if (name.isNotEmpty() && (input.attr("type") != "radio")) {
                            confirmData[name] = value
                        }
                    }
                    val radioName = confirmForm.select("input[type=radio]").attr("name")
                        .ifEmpty { "wizard_vehicle_enquiry_capture_confirm_vehicle[confirmed]" }
                    confirmData[radioName] = "Yes"

                    val finalResponse = Jsoup.connect(confirmAction)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .cookies(cookies)
                        .data(confirmData)
                        .method(Connection.Method.POST)
                        .timeout(10000)
                        .execute()

                    doc = finalResponse.parse()
                }

                // Extract full vehicle details from final page
                val finalKeys = doc.select(".govuk-summary-list__key, dt, .summary-item")
                val finalValues = doc.select(".govuk-summary-list__value, dd, .summary-value")
                for (i in 0 until minOf(finalKeys.size, finalValues.size)) {
                    val key = finalKeys[i].text().lowercase()
                    val value = finalValues[i].text()
                    if (key.contains("make") && make.isBlank()) make = value
                    if ((key.contains("colour") || key.contains("color")) && colour.isBlank()) colour = value
                    if (key.contains("fuel") && fuelType.isBlank()) fuelType = value
                    if ((key.contains("year") || key.contains("first registration")) && year.isBlank()) year = value
                    if (key.contains("vehicle status") && taxStatus.isBlank()) taxStatus = value
                }

                // Extract Tax and MOT status banners if present
                val pageText = doc.body().text()
                if (taxStatus.isBlank()) {
                    if (pageText.contains("Taxed", ignoreCase = true)) {
                        taxStatus = "Taxed"
                    } else if (pageText.contains("Untaxed", ignoreCase = true)) {
                        taxStatus = "Untaxed"
                    } else if (pageText.contains("SORN", ignoreCase = true)) {
                        taxStatus = "SORN"
                    }
                }

                if (motStatus.isBlank()) {
                    if (pageText.contains("valid MOT certificate", ignoreCase = true) || pageText.contains("MOT Valid", ignoreCase = true)) {
                        motStatus = "MOT Valid"
                    } else if (pageText.contains("No MOT", ignoreCase = true) || pageText.contains("MOT Expired", ignoreCase = true)) {
                        motStatus = "MOT Expired / No MOT"
                    }
                }

                if (make.isBlank() && taxStatus.isBlank()) {
                    return@withContext VehicleData(
                        errorMessage = context?.getString(R.string.no_data_found, cleanReg)
                            ?: "No data found for registration: $cleanReg"
                    )
                }

                return@withContext VehicleData(
                    make = make,
                    colour = colour,
                    fuelType = fuelType,
                    year = year,
                    taxStatus = taxStatus,
                    motStatus = motStatus
                )

            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext VehicleData(
                    errorMessage = context?.getString(R.string.error_message, e.message ?: "Unknown error")
                        ?: "Error: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }
}
