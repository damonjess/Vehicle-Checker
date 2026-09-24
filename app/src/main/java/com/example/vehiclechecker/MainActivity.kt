package com.example.vehiclechecker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnCheck = findViewById<Button>(R.id.btnCheck)
        val tvResult = findViewById<TextView>(R.id.tvResult)
        val etPlate = findViewById<EditText>(R.id.etPlate)

        btnCheck.setOnClickListener {
            val plate = etPlate.text.toString().trim()

            if (plate.isNotEmpty()) {
                tvResult.text = "Scraping..."
                CoroutineScope(Dispatchers.Main).launch {
                    val result = scrapeVehicleData(plate)
                    tvResult.text = result
                }
            } else {
                tvResult.text = "Please enter a number plate."
            }
        }
    }

    private suspend fun scrapeVehicleData(registration: String): String {
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
                    return@withContext "No data found for registration: $registration"
                }

                return@withContext "Vehicle: $vehicleMake\nStatus: $status"

            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext "Error: ${e.message}"
            }
        }
    }
}
