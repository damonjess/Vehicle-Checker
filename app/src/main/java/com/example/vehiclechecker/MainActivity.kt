package com.example.vehiclechecker

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    private lateinit var btnCheck: Button
    private lateinit var etPlate: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var recentSearchesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCheck = findViewById(R.id.btnCheck)
        etPlate = findViewById(R.id.etPlate)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        resultsContainer = findViewById(R.id.resultsContainer)
        recentSearchesContainer = findViewById(R.id.recentSearchesContainer)

        btnCheck.setOnClickListener { checkPlate(etPlate.text.toString()) }

        findViewById<TextView>(R.id.tvClear).setOnClickListener {
            lifecycleScope.launch {
                db.vehicleDao().clearHistory()
                recentSearchesContainer.removeAllViews()
            }
        }

        // Restore recent searches on open
        lifecycleScope.launch {
            showRecentSearches(db.vehicleDao().getAllRecentSearches())
        }
    }

    private fun checkPlate(plate: String) {
        val clean = plate.replace(" ", "").trim()
        if (clean.isEmpty()) {
            tvError.text = getString(R.string.enter_number_plate)
            tvError.visibility = View.VISIBLE
            return
        }

        etPlate.setText(clean.uppercase())
        tvError.visibility = View.GONE
        resultsContainer.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        btnCheck.isEnabled = false

        lifecycleScope.launch {
            // DVLA details via the free GOV.UK enquiry service
            val result = VehicleScraper.scrapeVehicleData(clean, this@MainActivity)
            progressBar.visibility = View.GONE
            btnCheck.isEnabled = true

            if (result.errorMessage != null) {
                tvError.text = result.errorMessage
                tvError.visibility = View.VISIBLE
                return@launch
            }

            bindResult(result)
            resultsContainer.visibility = View.VISIBLE
            saveAndShowHistory(result)
        }
    }

    private fun bindResult(result: VehicleData) {
        findViewById<TextView>(R.id.tvResultPlate).text = result.registration.ifEmpty { "--" }
        findViewById<TextView>(R.id.tvResultMake).text = result.make.ifEmpty { "--" }

        // Tax banner
        val cardTax = findViewById<View>(R.id.cardTax)
        val tvTaxStatus = findViewById<TextView>(R.id.tvTaxStatus)
        val tvTaxDue = findViewById<TextView>(R.id.tvTaxDue)
        when (result.taxStatus.uppercase()) {
            "TAXED" -> {
                cardTax.setBackgroundColor(ContextCompat.getColor(this, R.color.status_success))
                tvTaxStatus.text = "✓ Taxed"
            }
            "SORN" -> {
                cardTax.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warn))
                tvTaxStatus.text = "SORN (off the road)"
            }
            "" -> {
                cardTax.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
                tvTaxStatus.text = "Tax status unknown"
            }
            else -> {
                cardTax.setBackgroundColor(ContextCompat.getColor(this, R.color.status_danger))
                tvTaxStatus.text = "✗ Untaxed"
            }
        }
        tvTaxDue.text = result.taxDueDate

        // MOT banner
        val cardMot = findViewById<View>(R.id.cardMot)
        val tvMotStatus = findViewById<TextView>(R.id.tvMotStatus)
        val tvMotExpiry = findViewById<TextView>(R.id.tvMotExpiry)
        if (result.motStatus.equals("Valid", ignoreCase = true)) {
            cardMot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_success))
            tvMotStatus.text = "✓ MOT Valid"
        } else {
            cardMot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_danger))
            tvMotStatus.text = "✗ MOT Expired"
        }
        tvMotExpiry.text = result.motExpiryDate

        // Detail rows
        bindRow(R.id.rowMake, "Make", result.make)
        bindRow(R.id.rowModelYear, "Year of manufacture", result.yearOfManufacture)
        bindRow(R.id.rowFirstRegistered, "First registered", result.firstRegistered)
        bindRow(R.id.rowEngineSize, "Engine size", result.engineSize)
        bindRow(R.id.rowFuel, "Fuel type", result.fuelType)
        bindRow(R.id.rowCo2, "CO₂ emissions", result.co2Emissions)
        bindRow(R.id.rowColour, "Colour", result.colour)
        bindRow(R.id.rowWheelplan, "Wheelplan", result.wheelplan)
        bindRow(R.id.rowLastV5c, "Last V5C issued", result.lastV5cIssued)

        // MOT history from the free public GOV.UK MOT service
        loadMotHistory(result.registration.replace(" ", ""))
    }

    private fun loadMotHistory(registration: String) {
        val cardMotHistory = findViewById<View>(R.id.cardMotHistory)
        val tvSummary = findViewById<TextView>(R.id.tvMotHistorySummary)
        cardMotHistory.visibility = View.GONE

        lifecycleScope.launch {
            val history = MotHistoryScraper.fetchMotHistory(applicationContext, registration)

            if (history.errorMessage != null) {
                // Show the card with the reason so failures are visible, not silent
                tvSummary.text = history.errorMessage
                findViewById<View>(R.id.insightsPanel).visibility = View.GONE
                findViewById<View>(R.id.motTestsContainer).visibility = View.GONE
                findViewById<View>(R.id.mileageSection).visibility = View.GONE
                cardMotHistory.visibility = View.VISIBLE
                return@launch
            }

            if (history.tests.isEmpty()) {
                cardMotHistory.visibility = View.GONE
                return@launch
            }

            tvSummary.text = buildString {
                append(history.make)
                if (history.model.isNotBlank()) append(" ${history.model}")
                append(" · ${history.tests.size} test")
                if (history.tests.size != 1) append("s")
                if (history.motValidUntil.isNotBlank()) append(" · valid until ${history.motValidUntil}")
            }

            findViewById<View>(R.id.insightsPanel).visibility = View.VISIBLE
            findViewById<View>(R.id.motTestsContainer).visibility = View.VISIBLE
            findViewById<View>(R.id.mileageSection).visibility = View.VISIBLE

            bindMotInsights(history)
            bindMotTests(history)
            bindMileageTable(history)

            cardMotHistory.visibility = View.VISIBLE
        }
    }

    private fun bindMotInsights(history: MotHistoryData) {
        findViewById<TextView>(R.id.tvGapYears).text = history.gapYears.toString()
        findViewById<TextView>(R.id.tvPassRate).text = "${history.passRatePercent} %"

        // Pass / Pass+Advise / Fail stat rows with colored dots
        val statsContainer = findViewById<LinearLayout>(R.id.passStatsContainer)
        statsContainer.removeAllViews()
        val stats = listOf(
            Triple("Pass", history.passCount, R.color.status_success),
            Triple("Pass + Advise", history.passWithAdvisoriesCount, R.color.status_warn),
            Triple("Fail", history.failCount, R.color.status_danger)
        )
        stats.forEach { (label, value, colorRes) ->
            val row = layoutInflater.inflate(R.layout.view_stat_row, statsContainer, false)
            row.findViewById<View>(R.id.dotStat).setBackgroundColor(ContextCompat.getColor(this, colorRes))
            row.findViewById<TextView>(R.id.tvStatLabel).text = label
            row.findViewById<TextView>(R.id.tvStatValue).text = value.toString()
            statsContainer.addView(row)
        }

        val pie = findViewById<MotPieChart>(R.id.pieChart)
        pie.setSlices(
            listOf(
                MotPieChart.Slice(ContextCompat.getColor(this, R.color.status_success), history.passCount.toFloat(), "Pass"),
                MotPieChart.Slice(ContextCompat.getColor(this, R.color.status_warn), history.passWithAdvisoriesCount.toFloat(), "Pass + Advise"),
                MotPieChart.Slice(ContextCompat.getColor(this, R.color.status_danger), history.failCount.toFloat(), "Fail")
            )
        )
    }

    private fun bindMotTests(history: MotHistoryData) {
        val testsContainer = findViewById<LinearLayout>(R.id.motTestsContainer)
        testsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        history.tests.take(10).forEach { test ->
            val row = layoutInflater.inflate(R.layout.view_mot_test_row, testsContainer, false)
            val bannerRoot = row.findViewById<View>(R.id.bannerRoot)
            val detailRoot = row.findViewById<View>(R.id.detailRoot)
            val tvResult = row.findViewById<TextView>(R.id.tvTestResult)

            val color = ContextCompat.getColor(
                this,
                if (test.isPass) R.color.status_success else R.color.status_danger
            )
            bannerRoot.background?.setTint(color)
            tvResult.text = if (test.isPass) "Pass" else "Fail"

            row.findViewById<TextView>(R.id.tvTestDate).text = test.dateTested

            // Detail lines
            bindDetailLine(row, R.id.detailDate, "Date of Test:", test.dateTested)
            bindDetailLine(row, R.id.detailExpiry, "Expiry Date:", test.expiryDate.ifBlank { "Not recorded" })
            bindDetailLine(row, R.id.detailOdometer, "Odometer:", test.mileage.ifBlank { "Not recorded" })
            bindDetailLine(row, R.id.detailDifference, "Difference:", test.mileageDifferenceText ?: "First record")
            bindDetailLine(row, R.id.detailTestNumber, "Test Number:", test.testNumber.ifBlank { "Not recorded" })

            // Advisories
            val advisoriesSection = row.findViewById<View>(R.id.advisoriesSection)
            val advisoriesContainer = row.findViewById<LinearLayout>(R.id.advisoriesContainer)
            if (test.advisories.isEmpty()) {
                advisoriesSection.visibility = View.GONE
            } else {
                advisoriesSection.visibility = View.VISIBLE
                advisoriesContainer.removeAllViews()
                test.advisories.forEach { advisory ->
                    val tv = TextView(this).apply {
                        text = "• $advisory"
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    }
                    advisoriesContainer.addView(tv)
                }
            }

            // Expand/collapse on banner tap
            bannerRoot.setOnClickListener {
                val expanded = detailRoot.visibility == View.VISIBLE
                detailRoot.visibility = if (expanded) View.GONE else View.VISIBLE
                row.findViewById<TextView>(R.id.tvChevron).text = if (expanded) "›" else "⌄"
            }

            testsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            )
        }
    }

    private fun bindDetailLine(row: View, lineId: Int, label: String, value: String) {
        row.findViewById<View>(lineId)?.let { line ->
            line.findViewById<TextView>(R.id.tvLineLabel).text = label
            line.findViewById<TextView>(R.id.tvLineValue).text = value
        }
    }

    private fun bindMileageTable(history: MotHistoryData) {
        val rowsContainer = findViewById<LinearLayout>(R.id.mileageRowsContainer)
        rowsContainer.removeAllViews()

        val lastMileage = history.lastMileageMiles
        findViewById<TextView>(R.id.tvLastMileage).text =
            lastMileage?.let { String.format(java.util.Locale.UK, "%,d miles", it) } ?: "Not recorded"
        findViewById<TextView>(R.id.tvAvgMileage).text =
            history.averageMilesPerYear?.let { String.format(java.util.Locale.UK, "%,d miles", it) } ?: "Not recorded"

        history.tests.forEach { test ->
            if (test.mileageMiles == null) return@forEach
            val row = layoutInflater.inflate(R.layout.view_mileage_row, rowsContainer, false)
            row.findViewById<TextView>(R.id.tvMileageDate).text = test.dateTested
            row.findViewById<TextView>(R.id.tvMileageOdometer).text = test.mileage

            val tvDiff = row.findViewById<TextView>(R.id.tvMileageDiff)
            tvDiff.text = test.mileageDifferenceText ?: "--"
            tvDiff.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        test.mileageDifference == null -> R.color.text_secondary
                        test.mileageDifference!! < 0 -> R.color.status_danger
                        else -> R.color.status_success
                    }
                )
            )
            rowsContainer.addView(row)
        }
    }

    private fun bindRow(rowId: Int, label: String, value: String) {
        findViewById<View>(rowId)?.let { row ->
            row.findViewById<TextView>(R.id.tvRowLabel).text = label
            row.findViewById<TextView>(R.id.tvRowValue).text = value.ifBlank { "Not available" }
        }
    }

    private fun saveAndShowHistory(result: VehicleData) {
        lifecycleScope.launch {
            val reg = result.registration.replace(" ", "").uppercase()
            if (reg.isNotEmpty()) {
                db.vehicleDao().insertSearch(
                    VehicleEntity(
                        registration = reg,
                        make = result.make,
                        colour = result.colour
                    )
                )
            }
            showRecentSearches(db.vehicleDao().getAllRecentSearches())
        }
    }

    private fun showRecentSearches(searches: List<VehicleEntity>) {
        recentSearchesContainer.removeAllViews()
        if (searches.isEmpty()) return

        findViewById<View>(R.id.recentSearchesHeader).visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        searches.take(5).forEach { search ->
            val chip = TextView(this).apply {
                text = "${search.registration}  ·  ${search.make.ifBlank { "--" }}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_recent_chip)
                setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                setOnClickListener { checkPlate(search.registration) }
            }
            recentSearchesContainer.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            )
        }
    }
}
