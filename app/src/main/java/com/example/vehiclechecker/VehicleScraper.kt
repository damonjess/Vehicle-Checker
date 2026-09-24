package com.example.vehiclechecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object VehicleScraper {

    suspend fun scrapeVehicleData(registration: String, context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                // Replace this URL with your target vehicle checker URL
                val url = "https://example-public-checker.co.uk/check?reg=$registration"

                // Fetch HTML document with custom User-Agent
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(10000)
                    .get()

                // Strategy 1: Class or ID Selectors (e.g. .make-model-title or #vehicle-make)
                var vehicleMake = doc.select(".make-model-title, #vehicle-make, .vehicle-title").text()
                var status = doc.select(".ulez-compliance-badge, .tax-status, .compliance-status").text()

                // Strategy 2: Definition List / GOV.UK style summary list (<dt>Make</dt><dd>FORD</dd>)
                if (vehicleMake.isBlank()) {
                    vehicleMake = doc.select("dt:contains(Make) + dd, dt:contains(Vehicle) + dd").text()
                }
                if (status.isBlank()) {
                    status = doc.select("dt:contains(Status) + dd, dt:contains(MOT) + dd, dt:contains(Tax) + dd").text()
                }

                // Strategy 3: Table Row Selectors (<tr><td>Make</td><td>FORD</td></tr>)
                if (vehicleMake.isBlank()) {
                    vehicleMake = doc.select("tr:contains(Make) td:last-child").text()
                }
                if (status.isBlank()) {
                    status = doc.select("tr:contains(Tax) td:last-child, tr:contains(Status) td:last-child").text()
                }

                if (vehicleMake.isBlank() && status.isBlank()) {
                    return@withContext context.getString(R.string.no_data_found, registration)
                }

                return@withContext context.getString(R.string.vehicle_status, vehicleMake, status)

            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext context.getString(R.string.error_message, e.message ?: "Unknown error")
            }
        }
    }
}
