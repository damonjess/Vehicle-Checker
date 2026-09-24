package com.example.vehiclechecker

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnCheck = findViewById<Button>(R.id.btnCheck)
        val etPlate = findViewById<EditText>(R.id.etPlate)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvError = findViewById<TextView>(R.id.tvError)
        val resultsGrid = findViewById<GridLayout>(R.id.resultsGrid)

        // Individual Card Text Views
        val tvMake = findViewById<TextView>(R.id.tvMake)
        val tvColour = findViewById<TextView>(R.id.tvColour)
        val tvFuel = findViewById<TextView>(R.id.tvFuel)
        val tvYear = findViewById<TextView>(R.id.tvYear)
        val tvTax = findViewById<TextView>(R.id.tvTax)
        val tvMot = findViewById<TextView>(R.id.tvMot)

        btnCheck.setOnClickListener {
            val plate = etPlate.text.toString().replace(" ", "").trim()

            if (plate.isNotEmpty()) {
                // Reset UI for loading state
                resultsGrid.visibility = View.GONE
                tvError.visibility = View.GONE
                progressBar.visibility = View.VISIBLE

                lifecycleScope.launch {
                    // Fetch data using your Jsoup scraper
                    val result = VehicleScraper.scrapeVehicleData(plate, this@MainActivity)

                    // Hide spinner when finished
                    progressBar.visibility = View.GONE

                    if (result.errorMessage != null) {
                        tvError.text = result.errorMessage
                        tvError.visibility = View.VISIBLE
                    } else {
                        // Bind the scraped data to the cards
                        tvMake.text = result.make.ifEmpty { "--" }
                        tvColour.text = result.colour.ifEmpty { "--" }
                        tvFuel.text = result.fuelType.ifEmpty { "--" }
                        tvYear.text = result.year.ifEmpty { "--" }
                        tvTax.text = result.taxStatus.ifEmpty { "--" }
                        tvMot.text = result.motStatus.ifEmpty { "--" }

                        // Reveal the grid
                        resultsGrid.visibility = View.VISIBLE
                    }
                }
            } else {
                tvError.text = getString(R.string.enter_number_plate)
                tvError.visibility = View.VISIBLE
            }
        }
    }
}
