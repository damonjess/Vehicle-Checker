package com.example.vehiclechecker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    private var currentReg: String = ""
    private var currentVehicle: VehicleData? = null
    private var currentMot: MotHistoryData? = null

    private lateinit var btnCheck: Button
    private lateinit var etPlate: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var recentSearchesContainer: LinearLayout

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val plate = result.data?.getStringExtra(PlateScannerActivity.RESULT_PLATE)
            if (!plate.isNullOrBlank()) {
                etPlate.setText(plate)
                checkPlate(plate)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCheck = findViewById(R.id.btnCheck)
        etPlate = findViewById(R.id.etPlate)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        resultsContainer = findViewById(R.id.resultsContainer)
        recentSearchesContainer = findViewById(R.id.recentSearchesContainer)

        btnCheck.setOnClickListener { checkPlate(etPlate.text.toString()) }

        // Camera plate scanner
        findViewById<View>(R.id.btnScan).setOnClickListener {
            scanLauncher.launch(Intent(this, PlateScannerActivity::class.java))
        }

        // Share button: generate + share the PDF report
        findViewById<View>(R.id.btnShareReport).setOnClickListener {
            val vehicle = currentVehicle ?: return@setOnClickListener
            lifecycleScope.launch {
                val file = ReportPdfBuilder.build(applicationContext, vehicle, currentMot)
                ShareUtils.sharePdf(this@MainActivity, file)
            }
        }

        // Favourite toggle for the current vehicle
        findViewById<TextView>(R.id.btnFavourite).setOnClickListener {
            if (currentReg.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                val saved = db.vehicleDao().getAllRecentSearches()
                    .firstOrNull { it.registration == currentReg }
                val newState = saved?.isFavourite != true
                db.vehicleDao().setFavourite(currentReg, newState)
                updateFavouriteIcon(newState)
            }
        }

        findViewById<TextView>(R.id.tvClear).setOnClickListener {
            lifecycleScope.launch {
                db.vehicleDao().clearHistory()
                recentSearchesContainer.removeAllViews()
            }
        }

        // Daily expiry reminder worker + notification permission
        ReminderScheduler.scheduleDailyCheck(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // Restore recent searches on open
        lifecycleScope.launch {
            showRecentSearches(db.vehicleDao().getAllRecentSearches())
        }

        // My Notes: save / delete for the currently shown vehicle
        findViewById<Button>(R.id.btnSaveNote).setOnClickListener {
            val text = findViewById<EditText>(R.id.etNote).text.toString().trim()
            if (text.isEmpty() || currentReg.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                db.noteDao().upsert(NoteEntity(registration = currentReg, note = text))
                findViewById<Button>(R.id.btnDeleteNote).visibility = View.VISIBLE
            }
        }
        findViewById<Button>(R.id.btnDeleteNote).setOnClickListener {
            if (currentReg.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                db.noteDao().delete(currentReg)
                findViewById<EditText>(R.id.etNote).setText("")
                findViewById<Button>(R.id.btnDeleteNote).visibility = View.GONE
            }
        }
    }

    private fun updateFavouriteIcon(favourite: Boolean) {
        findViewById<TextView>(R.id.btnFavourite).apply {
            text = if (favourite) "★ Saved" else "☆ Save"
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (favourite) R.color.brand_yellow else R.color.white
                )
            )
        }
    }

    private fun showFavouriteState(registration: String) {
        lifecycleScope.launch {
            val saved = db.vehicleDao().getAllRecentSearches()
                .firstOrNull { it.registration == registration }
            updateFavouriteIcon(saved?.isFavourite == true)
        }
    }

    /** Loads any saved note for this vehicle into the My Notes card. */
    private fun loadNote(registration: String) {
        lifecycleScope.launch {
            val note = db.noteDao().getNote(registration)
            findViewById<EditText>(R.id.etNote).setText(note?.note ?: "")
            findViewById<Button>(R.id.btnDeleteNote).visibility =
                if (note != null) View.VISIBLE else View.GONE
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
            // Offline cache: show any previously saved result instantly, then refresh live
            val cached = db.cachedVehicleDao().get(clean.uppercase())
            if (cached != null) {
                progressBar.visibility = View.GONE
                val cachedVehicle = cached.toVehicle()
                val cachedMot = cached.toMot()
                currentReg = cachedVehicle.registration
                currentVehicle = cachedVehicle
                currentMot = cachedMot
                bindResult(cachedVehicle)
                cachedMot?.let { bindMotSection(it) }
                resultsContainer.visibility = View.VISIBLE
                loadNote(currentReg)
                showFavouriteState(currentReg)
            }

            // DVLA details via the free GOV.UK enquiry service
            val result = VehicleScraper.scrapeVehicleData(clean, this@MainActivity)
            progressBar.visibility = View.GONE
            btnCheck.isEnabled = true

            if (result.errorMessage != null) {
                if (cached == null) {
                    tvError.text = result.errorMessage
                    tvError.visibility = View.VISIBLE
                }
                return@launch // cached results (if any) stay on screen
            }

            currentReg = result.registration.replace(" ", "").uppercase()
            currentVehicle = result
            bindResult(result)
            resultsContainer.visibility = View.VISIBLE
            saveAndShowHistory(result)
            loadNote(currentReg)
            showFavouriteState(currentReg)

            // Fresh MOT history + cache the combined payload for offline use
            val mot = MotHistoryScraper.fetchMotHistory(applicationContext, currentReg)
            currentMot = mot.takeIf { it.errorMessage == null }
            currentMot?.let { bindMotSection(it) }
            db.cachedVehicleDao().upsert(CachedVehicleEntity.fromData(result, currentMot))
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
        bindRow(R.id.rowEuroStatus, "Euro status", result.euroStatus)
        bindRow(R.id.rowTypeApproval, "Type approval", result.typeApproval)
        bindRow(R.id.rowExport, "Exported", result.exportMarker)

        // Costs & compliance: estimated road tax + ULEZ / CAZ check
        val taxEstimate = TaxEstimator.estimate(
            result.firstRegistered, result.yearOfManufacture, result.engineSize,
            result.fuelType, result.co2Emissions
        )
        findViewById<TextView>(R.id.tvTaxCost).text =
            taxEstimate.amountPounds?.let { "£$it / year" } ?: "Unavailable"

        val ulez = UlezChecker.check(result.fuelType, result.euroStatus, result.firstRegistered)
        val tvUlez = findViewById<TextView>(R.id.tvUlez)
        tvUlez.text = when (ulez.compliant) {
            true -> "✓ ${ulez.title}"
            false -> "✗ ${ulez.title}"
            null -> ulez.title
        }
        tvUlez.setTextColor(
            when (ulez.compliant) {
                true -> 0xFF7FD8A8.toInt()
                false -> 0xFFFFB4A9.toInt()
                null -> 0xFFFFFFFF.toInt()
            }
        )
        findViewById<TextView>(R.id.tvComplianceNote).text =
            "${taxEstimate.note}. ${ulez.detail} Rates are estimates — confirm on GOV.UK / TfL."
    }

    private fun bindMotSection(history: MotHistoryData) {
        val cardMotHistory = findViewById<View>(R.id.cardMotHistory)
        val tvSummary = findViewById<TextView>(R.id.tvMotHistorySummary)

        if (history.errorMessage != null) {
            // Show the card with the reason so failures are visible, not silent
            tvSummary.text = history.errorMessage
            findViewById<View>(R.id.insightsPanel).visibility = View.GONE
            findViewById<View>(R.id.motTestsContainer).visibility = View.GONE
            findViewById<View>(R.id.mileageSection).visibility = View.GONE
            findViewById<View>(R.id.cardRecalls).visibility = View.GONE
            cardMotHistory.visibility = View.VISIBLE
            return
        }

        if (history.tests.isEmpty()) {
            cardMotHistory.visibility = View.GONE
            return
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
        bindRecalls(history)

        cardMotHistory.visibility = View.VISIBLE
    }

    private fun bindRecalls(history: MotHistoryData) {
        val cardRecalls = findViewById<View>(R.id.cardRecalls)
        val tvTitle = findViewById<TextView>(R.id.tvRecallTitle)
        val tvDetail = findViewById<TextView>(R.id.tvRecallDetail)

        cardRecalls.visibility = View.VISIBLE
        when (history.recallStatus) {
            RecallStatus.NONE -> {
                tvTitle.text = "✓ Safety Recalls: none outstanding"
                tvTitle.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                tvDetail.text = history.recallDetail.ifBlank {
                    "No outstanding safety recalls recorded for this vehicle."
                }
            }
            RecallStatus.OUTSTANDING -> {
                tvTitle.text = "⚠ Outstanding safety recall"
                tvTitle.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
                tvDetail.text = history.recallDetail.ifBlank {
                    "This vehicle has an unfixed safety recall. Contact a dealer to arrange the free repair."
                }
            }
            RecallStatus.UNKNOWN -> {
                tvTitle.text = "Safety Recalls"
                tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                tvDetail.text = history.recallDetail.ifBlank {
                    "Recall information is not available for this vehicle. Check with the manufacturer."
                }
            }
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
            row.findViewById<View>(R.id.dotStat)
                .setBackgroundColor(ContextCompat.getColor(this, colorRes))
            row.findViewById<TextView>(R.id.tvStatLabel).text = label
            row.findViewById<TextView>(R.id.tvStatValue).text = value.toString()
            statsContainer.addView(row)
        }

        val pie = findViewById<MotPieChart>(R.id.pieChart)
        pie.setSlices(
            listOf(
                MotPieChart.Slice(
                    ContextCompat.getColor(this, R.color.status_success),
                    history.passCount.toFloat(), "Pass"
                ),
                MotPieChart.Slice(
                    ContextCompat.getColor(this, R.color.status_warn),
                    history.passWithAdvisoriesCount.toFloat(), "Pass + Advise"
                ),
                MotPieChart.Slice(
                    ContextCompat.getColor(this, R.color.status_danger),
                    history.failCount.toFloat(), "Fail"
                )
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
        // Mileage anomaly warnings (possible clocking) — or a clean bill of health
        val anomaliesContainer = findViewById<LinearLayout>(R.id.anomaliesContainer)
        anomaliesContainer.removeAllViews()
        val anomalies = history.mileageAnomalies
        if (anomalies.isEmpty()) {
            if (history.tests.count { it.mileageMiles != null } >= 2) {
                val okRow = layoutInflater.inflate(R.layout.view_mileage_anomaly, anomaliesContainer, false)
                okRow.background?.setTint(ContextCompat.getColor(this, R.color.status_success))
                okRow.findViewById<TextView>(R.id.tvAnomalyTitle).apply {
                    text = "✓ No mileage issues spotted"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                }
                okRow.findViewById<TextView>(R.id.tvAnomalyDetail).apply {
                    text = "Odometer readings increase consistently across every recorded test."
                    setTextColor(0xE6FFFFFF.toInt())
                }
                anomaliesContainer.addView(okRow)
            }
        } else {
            anomalies.forEach { anomaly ->
                val row = layoutInflater.inflate(R.layout.view_mileage_anomaly, anomaliesContainer, false)
                row.findViewById<TextView>(R.id.tvAnomalyTitle).text = "⚠ ${anomaly.title}"
                row.findViewById<TextView>(R.id.tvAnomalyDetail).text = anomaly.detail
                anomaliesContainer.addView(row)
            }
        }

        // Annual mileage bar chart (miles between consecutive tests, per year)
        val chart = findViewById<MileageBarChart>(R.id.mileageChart)
        val byYear = linkedMapOf<Int, Float>()
        history.tests.forEach { test ->
            val year = test.yearTested ?: return@forEach
            val miles = test.mileageMiles?.toFloat() ?: return@forEach
            val prev = history.tests.getOrNull(history.tests.indexOf(test) + 1)
            val prevMiles = prev?.mileageMiles?.toFloat()
            val driven = if (prevMiles != null) (miles - prevMiles).coerceAtLeast(0f) else 0f
            byYear[year] = (byYear[year] ?: 0f) + driven
        }
        chart.setEntries(byYear.entries.map { MileageBarChart.Entry(it.key, it.value) }.reversed())

        val rowsContainer = findViewById<LinearLayout>(R.id.mileageRowsContainer)
        rowsContainer.removeAllViews()

        val lastMileage = history.lastMileageMiles
        findViewById<TextView>(R.id.tvLastMileage).text =
            lastMileage?.let { String.format(java.util.Locale.UK, "%,d miles", it) } ?: "Not recorded"
        findViewById<TextView>(R.id.tvAvgMileage).text =
            history.averageMilesPerYear?.let { String.format(java.util.Locale.UK, "%,d miles", it) }
                ?: "Not recorded"

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
                val existing = db.vehicleDao().getAllRecentSearches()
                    .firstOrNull { it.registration == reg }

                db.vehicleDao().insertSearch(
                    VehicleEntity(
                        registration = reg,
                        make = result.make,
                        colour = result.colour,
                        isFavourite = existing?.isFavourite ?: false,
                        taxDueEpochMs = DateUtils.parseFlexible(result.taxDueDate),
                        motExpiryEpochMs = DateUtils.parseFlexible(result.motExpiryDate)
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
                val star = if (search.isFavourite) "★ " else ""
                val countdowns = buildList {
                    search.motExpiryEpochMs?.let {
                        add("MOT ${DateUtils.daysUntil(it)}d")
                    }
                    search.taxDueEpochMs?.let {
                        add("Tax ${DateUtils.daysUntil(it)}d")
                    }
                }
                text = "$star${search.registration}  ·  ${search.make.ifBlank { "--" }}" +
                    (if (countdowns.isNotEmpty()) "  ·  ${countdowns.joinToString("  ")}" else "")
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_recent_chip)
                setPadding(
                    (14 * density).toInt(), (10 * density).toInt(),
                    (14 * density).toInt(), (10 * density).toInt()
                )
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
