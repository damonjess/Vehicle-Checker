package com.example.vehiclechecker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
                tvResult.text = getString(R.string.scraping)
                lifecycleScope.launch {
                    val result = VehicleScraper.scrapeVehicleData(plate, this@MainActivity)
                    tvResult.text = result
                }
            } else {
                tvResult.text = getString(R.string.enter_number_plate)
            }
        }
    }
}
