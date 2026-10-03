package com.example.vehiclechecker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.noties.markwon.Markwon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    /** How long the AI button waits on a running MOT fetch before analysing without it. */
    private val AI_MOT_WAIT_MS = 10_000L

    /** Minimum gap between streamed render updates, so Markwon isn't re-parsing per token. */
    private val AI_RENDER_INTERVAL_MS = 250L

    private var searchJob: Job? = null
    private var currentReg: String = ""
    private var currentVehicle: VehicleData? = null
    private var currentMot: MotHistoryData? = null

    private lateinit var btnCheck: Button
    private lateinit var etPlate: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var recentSearchesContainer: LinearLayout
    private lateinit var markwon: Markwon

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val plate = result.data?.getStringExtra(PlateScannerActivity.RESULT_PLATE)
            if (!plate.isNullOrBlank()) {
                etPlate.setText(plate)
                // A scan is usually about the MOT, so open its own screen straight away;
                // the full report keeps loading behind it.
                checkPlate(plate)
                startActivity(MotHistoryActivity.intent(this, plate))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        markwon = Markwon.create(this)

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
                db.cachedVehicleDao().purgeOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
            }
        }

        // Retry button on the MOT card (shown when a fetch fails)
        findViewById<View>(R.id.btnRetryMot).setOnClickListener { retryMotFetch() }

        // Fallback: the MOT site blocks automated clients, so let the user view the
        // official page in their real browser (where the security check passes).
        findViewById<View>(R.id.btnOpenMotBrowser).setOnClickListener {
            if (currentReg.isEmpty()) return@setOnClickListener
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            "https://www.check-mot.service.gov.uk/results?registration=$currentReg&checkRecalls=true"
                        )
                    )
                )
            } catch (_: Exception) {
            }
        }

        // The report keeps only a compact MOT summary; the full record has its own screen.
        findViewById<View>(R.id.btnOpenMotHistory).setOnClickListener { openMotHistory() }

        // Dev/debug hook: `adb shell am start -n com.example.vehiclechecker/.MainActivity --es plate ABC123`
        // runs a lookup immediately on launch.
        intent?.getStringExtra("plate")?.takeIf { it.isNotBlank() }?.let { plate ->
            etPlate.setText(plate.uppercase())
            checkPlate(plate)
        }

        // Dev/debug hook: `adb shell am start -n com.example.vehiclechecker/.MainActivity --ez refresh true`
        // runs the weekly tax/MOT status refresh immediately instead of waiting for its schedule.
        if (intent?.getBooleanExtra("refresh", false) == true) {
            ReminderScheduler.runStatusRefreshNow(this)
        }

        // NOTE: no background challenge warm-up here — running it alongside a real fetch
        // means two WebViews hitting the bot challenge at once, which escalates the block.

        // Daily expiry reminder worker + notification permission
        ReminderScheduler.scheduleDailyCheck(this)
        // Weekly live re-check of tax/MOT dates so those reminders stay accurate
        ReminderScheduler.scheduleWeeklyStatusRefresh(this)
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

        // AI Mechanic Analysis
        val btnAskAi = findViewById<Button>(R.id.btnAskAi)
        val tvAiResult = findViewById<TextView>(R.id.tvAiResult)
        val aiProgressBar = findViewById<ProgressBar>(R.id.aiProgressBar)

        btnAskAi.setOnClickListener {
            val vehicle = currentVehicle ?: return@setOnClickListener

            // Update UI to loading state
            btnAskAi.isEnabled = false
            tvAiResult.visibility = View.GONE
            aiProgressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                // The MOT fetch (WebView challenge) runs for 15-45s when the site is slow. Wait
                // for it briefly so a normally-fast fetch still informs the analysis, but never
                // let it stall the button for three quarters of a minute — past the window we
                // analyse with whatever we already have.
                if (currentMot == null && searchJob?.isActive == true) {
                    withTimeoutOrNull(AI_MOT_WAIT_MS) { searchJob?.join() }
                }

                // Ask Gemini! The reply streams in, so the answer starts appearing as soon as
                // the first tokens land instead of after the whole response has been generated.
                var lastRenderAt = 0L
                val analysis = VehicleAiAnalyst.analyze(vehicle, currentMot) { partial ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRenderAt < AI_RENDER_INTERVAL_MS) return@analyze
                    lastRenderAt = now
                    runOnUiThread {
                        markwon.setMarkdown(tvAiResult, partial)
                        tvAiResult.visibility = View.VISIBLE
                        aiProgressBar.visibility = View.GONE
                    }
                }

                if (!analysis.startsWith("AI Analysis is currently unavailable")) {
                    val existingCache = db.cachedVehicleDao().get(currentReg)
                    if (existingCache != null) {
                        // Update the existing cache with the new report
                        db.cachedVehicleDao().upsert(existingCache.copy(aiReport = analysis))
                    }
                }

                // Update UI with results
                aiProgressBar.visibility = View.GONE
                markwon.setMarkdown(tvAiResult, analysis)
                tvAiResult.visibility = View.VISIBLE
                btnAskAi.text = "Refresh Analysis"
                btnAskAi.isEnabled = true
            }
        }

        findViewById<Button>(R.id.btnAddService).setOnClickListener {
            if (currentReg.isEmpty()) return@setOnClickListener
            showAddServiceDialog()
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

    private fun showAddServiceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val dateInput = EditText(this).apply { hint = "Date (e.g. 12 Oct 2025)" }
        val mileageInput = EditText(this).apply { hint = "Mileage"; inputType = InputType.TYPE_CLASS_NUMBER }
        val descInput = EditText(this).apply { hint = "Description (e.g. Oil change)" }
        val costInput = EditText(this).apply { hint = "Cost (£)" }

        layout.addView(dateInput)
        layout.addView(mileageInput)
        layout.addView(descInput)
        layout.addView(costInput)

        AlertDialog.Builder(this)
            .setTitle("Add Service Log")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val log = ServiceLogEntity(
                    registration = currentReg,
                    date = dateInput.text.toString(),
                    mileage = mileageInput.text.toString(),
                    description = descInput.text.toString(),
                    cost = costInput.text.toString()
                )
                lifecycleScope.launch {
                    db.serviceLogDao().insertLog(log)
                    loadServiceLogs() // Refresh the UI
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadServiceLogs() {
        if (currentReg.isEmpty()) return

        lifecycleScope.launch {
            val logs = db.serviceLogDao().getLogs(currentReg)
            val container = findViewById<LinearLayout>(R.id.serviceLogsContainer)
            container.removeAllViews()

            if (logs.isEmpty()) {
                val emptyText = TextView(this@MainActivity).apply {
                    text = "No service history logged."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 13f
                }
                container.addView(emptyText)
                return@launch
            }

            logs.forEach { log ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                }

                val header = TextView(this@MainActivity).apply {
                    text = "${log.date}  ·  ${log.mileage} miles  ·  £${log.cost}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                val desc = TextView(this@MainActivity).apply {
                    text = log.description
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setTypeface(null, Typeface.BOLD)
                }

                row.addView(header)
                row.addView(desc)

                // Optional: Long click to delete
                row.setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete Log?")
                        .setPositiveButton("Yes") { _, _ ->
                            lifecycleScope.launch {
                                db.serviceLogDao().deleteLog(log.id)
                                loadServiceLogs()
                            }
                        }
                        .setNegativeButton("No", null)
                        .show()
                    true
                }

                container.addView(row)

                // Add a divider
                val divider = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        setMargins(0, 8, 0, 8)
                    }
                    setBackgroundColor(0xFFE7E9EA.toInt())
                }
                container.addView(divider)
            }
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

        // Clear current state to avoid bleeding data from a previous search
        currentMot = null
        currentVehicle = null

        // Reset AI Analyst UI state
        findViewById<TextView>(R.id.tvAiResult).visibility = View.GONE
        findViewById<ProgressBar>(R.id.aiProgressBar).visibility = View.GONE
        findViewById<Button>(R.id.btnAskAi).apply {
            text = "Analyze Car"
            isEnabled = true
        }

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            // Offline cache: show any previously saved result instantly, then refresh live.
            // A cached MOT that is a live-result with zero tests is a poisoned entry from an
            // earlier parser bug — ignore it so the charts don't render blank forever.
            val cached = db.cachedVehicleDao().get(clean.uppercase())
            val cachedMotRaw = cached?.toMot()
            
            fun isUsable(mot: MotHistoryData?): Boolean =
                mot != null && mot.errorMessage == null && mot.tests.isNotEmpty()
            val cachedMot = cachedMotRaw?.takeIf { isUsable(it) }
            
            Log.d("MotCache", "reg=${clean.uppercase()} cachedRow=${cached != null} motJsonLen=${cached?.motJson?.length} raw tests=${cachedMotRaw?.tests?.size} err=${cachedMotRaw?.errorMessage} usable=${cachedMot != null}")
            
            val nowMs = System.currentTimeMillis()
            val isCacheFresh = cached != null && cachedMot != null && (nowMs - cached.timestamp < 24L * 60 * 60 * 1000L)

            if (cached != null) {
                progressBar.visibility = View.GONE
                val cachedVehicle = cached.toVehicle()
                currentReg = cachedVehicle.registration
                currentVehicle = cachedVehicle
                currentMot = cachedMot
                bindResult(cachedVehicle)
                if (cachedMot != null) {
                    bindMotSummary(cachedMot)
                } else {
                    findViewById<View>(R.id.cardMotSummary).visibility = View.GONE
                }
                resultsContainer.visibility = View.VISIBLE
                loadNote(currentReg)
                showFavouriteState(currentReg)
                loadServiceLogs()

                val tvAiResult = findViewById<TextView>(R.id.tvAiResult)
                val btnAskAi = findViewById<Button>(R.id.btnAskAi)
                if (!cached.aiReport.isNullOrBlank()) {
                    markwon.setMarkdown(tvAiResult, cached.aiReport)
                    tvAiResult.visibility = View.VISIBLE
                    btnAskAi.text = "Refresh Analysis"
                } else {
                    tvAiResult.visibility = View.GONE
                    btnAskAi.text = "Analyze Car"
                }
            }

            if (isCacheFresh) {
                Log.d("MotCache", "Cache is under 24h old, skipping live fetch for $clean")
                btnCheck.isEnabled = true
                
                // Keep "Recent searches" ordering updated even if we skip the live fetch
                val cachedVehicle = cached.toVehicle()
                saveAndShowHistory(cachedVehicle)
                
                return@launch
            }

            // DVLA details via the free GOV.UK enquiry service
            val result = VehicleScraper.scrapeVehicleData(clean, this@MainActivity)

            // Ensure registration is set even if DVLA scraping failed completely
            val liveVehicle = if (result.registration.isBlank()) {
                result.copy(registration = clean.uppercase())
            } else {
                result
            }

            if (liveVehicle.errorMessage != null) {
                if (cached == null) {
                    tvError.text = liveVehicle.errorMessage
                    tvError.visibility = View.VISIBLE
                }
                // Do NOT return early. Continue to fetch MOT history and show the banners.
            }

            currentReg = liveVehicle.registration.replace(" ", "").uppercase()
            currentVehicle = liveVehicle
            bindResult(liveVehicle)
            resultsContainer.visibility = View.VISIBLE

            // The MOT fetch (WebView challenge) can take 15-45s. Show the card right away
            // with a loading note so it's never silently absent — bindMotSection() swaps in
            // the real content (or a visible error reason) when the fetch settles.
            if (currentMot == null) showMotSummaryLoading()
            
            // Only save to history if DVLA succeeded, to avoid cluttering recent searches with failed plates
            if (liveVehicle.errorMessage == null) {
                saveAndShowHistory(liveVehicle)
            }
            
            loadNote(currentReg)
            showFavouriteState(currentReg)
            loadServiceLogs()

            // Fresh MOT history: the official DVSA API when configured, then the public GOV.UK
            // WebView scraper. Shared with the dedicated MOT screen, which may be asking for the
            // same plate at the same moment, so the page is only ever rendered once.
            val mot = MotHistoryRepository.load(this@MainActivity, currentReg)

            // Stale check: if user searched for another plate while this fetch was running, ignore result
            if (currentReg != clean.uppercase()) return@launch

            val existingCache = db.cachedVehicleDao().get(currentReg)

            if (isUsable(mot) && mot != null) {
                currentMot = mot
                bindMotSummary(mot)
                db.cachedVehicleDao().upsert(
                    CachedVehicleEntity.fromData(liveVehicle, mot, existingCache?.aiReport)
                )
            } else {
                // Live fetch failed (could be rate limit, unconfigured API, missing vehicle, network error)
                // Fall back to any usable cached MOT instead of collapsing the card to an error.
                val fallback = currentMot?.takeIf { isUsable(it) } ?: cachedMot
                if (fallback != null) {
                    currentMot = fallback
                    bindMotSummary(fallback)
                    db.cachedVehicleDao().upsert(
                        CachedVehicleEntity.fromData(liveVehicle, fallback, existingCache?.aiReport)
                            .copy(timestamp = existingCache?.timestamp ?: System.currentTimeMillis())
                    )
                    Toast.makeText(this@MainActivity, "Showing saved MOT data (live fetch failed)", Toast.LENGTH_SHORT).show()
                } else {
                    bindMotSummary(mot ?: MotHistoryData(registration = currentReg, errorMessage = "Could not load MOT history — please try again."))
                }
            }

            progressBar.visibility = View.GONE
            btnCheck.isEnabled = true
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
        } else if (result.motStatus.isNotBlank()) {
            cardMot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_danger))
            tvMotStatus.text = "✗ MOT Expired"
        } else {
            cardMot.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvMotStatus.text = "MOT status unknown"
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

        // 1. Calculate the age of the V5C logbook
        val v5cEpoch = DateUtils.parseFlexible(result.lastV5cIssued)
        var v5cDisplay = result.lastV5cIssued

        if (v5cEpoch != null) {
            val daysAgo = -DateUtils.daysUntil(v5cEpoch) // Negative because it's in the past
            val monthsAgo = daysAgo / 30

            // 2. Append the Red Flag Warning if it's suspiciously new
            if (monthsAgo < 6) {
                v5cDisplay += "\n⚠️ Issued $monthsAgo months ago. (High-risk turnover. Ask seller why they are selling so soon)."
            } else {
                v5cDisplay += "\n✓ Held for $monthsAgo months."
            }
        }

        // 3. Bind the updated string to the row
        bindRow(R.id.rowLastV5c, "Last V5C issued", v5cDisplay)

        findViewById<View>(R.id.rowLastV5c)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("V5C Logbook Check")
                .setMessage("When viewing this car, check the date on the physical paper V5C.\n\nIt MUST match '${result.lastV5cIssued}'. If the paper has an older date, the seller is showing you an invalid logbook and cannot legally transfer ownership.")
                .setPositiveButton("Got it", null)
                .show()
        }

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

        // Fuel + tax for a year; refined below once the MOT mileage arrives
        updateRunningCost()

        // Outstanding Finance & Logbook Risk Check
        bindFinanceCheck(result)

        // Keeper & Ownership History Timeline
        bindKeeperHistory(result)
    }

    private fun bindKeeperHistory(result: VehicleData) {
        val keeperInfo = KeeperHistoryCalculator.calculate(result)

        val tvBadge = findViewById<TextView>(R.id.tvKeeperBadge)
        val tvFirstReg = findViewById<TextView>(R.id.tvKeeperFirstReg)
        val tvTotalAge = findViewById<TextView>(R.id.tvTotalVehicleAge)
        val tvV5cDate = findViewById<TextView>(R.id.tvKeeperV5cDate)
        val tvDuration = findViewById<TextView>(R.id.tvKeeperDuration)
        val tvNote = findViewById<TextView>(R.id.tvKeeperNote)

        tvBadge.text = keeperInfo.stabilityBadgeText
        tvFirstReg.text = keeperInfo.firstRegisteredDate
        tvTotalAge.text = keeperInfo.totalVehicleAgeText
        tvV5cDate.text = keeperInfo.currentV5cDate
        tvDuration.text = keeperInfo.currentKeeperDurationText
        tvNote.text = keeperInfo.stabilityNote
    }

    private fun bindFinanceCheck(result: VehicleData) {
        val finance = FinanceChecker.checkFinanceStatus(result)

        val tvRiskBadge = findViewById<TextView>(R.id.tvFinanceRiskBadge)
        val tvStatusTitle = findViewById<TextView>(R.id.tvFinanceStatusTitle)
        val tvStatusDetail = findViewById<TextView>(R.id.tvFinanceStatusDetail)
        val btnChecklist = findViewById<View>(R.id.btnFinanceChecklist)
        val btnHpiCheck = findViewById<View>(R.id.btnRunHpiCheck)

        tvRiskBadge.text = finance.riskBadgeText
        tvRiskBadge.setBackgroundColor(
            when (finance.riskLevel) {
                FinanceChecker.RiskLevel.LOW -> ContextCompat.getColor(this, R.color.status_success)
                FinanceChecker.RiskLevel.MEDIUM -> ContextCompat.getColor(this, R.color.status_warn)
                FinanceChecker.RiskLevel.HIGH -> ContextCompat.getColor(this, R.color.status_danger)
            }
        )

        tvStatusTitle.text = finance.statusTitle
        tvStatusDetail.text = finance.statusDetail

        btnChecklist.setOnClickListener {
            val message = finance.checklist.joinToString("\n\n")
            AlertDialog.Builder(this)
                .setTitle("Finance & Ownership Clearance Checklist")
                .setMessage(message)
                .setPositiveButton("Understood", null)
                .show()
        }

        btnHpiCheck.setOnClickListener {
            FinanceChecker.openHpiRegisterCheck(this, result.registration)
        }
    }

    /**
     * Fuel plus road tax for a year. The annual mileage comes from the MOT odometer trail and the
     * economy from the DVLA fuel type and engine size, so this is refined as soon as the MOT
     * history lands (and runs again on the cached path, where it is already available).
     */
    private fun updateRunningCost() {
        val vehicle = currentVehicle ?: return
        val tax = TaxEstimator.estimate(
            vehicle.firstRegistered, vehicle.yearOfManufacture, vehicle.engineSize,
            vehicle.fuelType, vehicle.co2Emissions
        ).amountPounds

        val estimate = RunningCostCalculator.estimate(
            fuelType = vehicle.fuelType,
            engineSize = vehicle.engineSize,
            motAnnualMiles = currentMot?.averageMilesPerYear,
            taxPerYear = tax
        )

        findViewById<TextView>(R.id.tvFuelCost).text = "£${estimate.fuelCostPerYear} / year"
        findViewById<TextView>(R.id.tvRunningTotal).text = "£${estimate.totalPerYear} / year"
        findViewById<TextView>(R.id.tvRunningCostNote).text = estimate.assumption
    }

    /**
     * Compact MOT summary shown in the report. The full record — charts, every test, the mileage
     * trail and recalls — lives on its own screen ([MotHistoryActivity]).
     */
    private fun bindMotSummary(history: MotHistoryData) {
        val cardMotSummary = findViewById<View>(R.id.cardMotSummary)
        val tvLine = findViewById<TextView>(R.id.tvMotSummaryLine)
        val tvStats = findViewById<TextView>(R.id.tvMotSummaryStats)

        if (history.tests.isEmpty()) {
            if (history.errorMessage != null) {
                // Show the reason so failures are visible, not silent
                tvLine.text = history.errorMessage
                tvStats.visibility = View.GONE
                findViewById<View>(R.id.btnOpenMotHistory).visibility = View.GONE
                findViewById<View>(R.id.btnRetryMot).visibility = View.VISIBLE
                findViewById<View>(R.id.btnOpenMotBrowser).visibility = View.VISIBLE
                cardMotSummary.visibility = View.VISIBLE
            } else {
                cardMotSummary.visibility = View.GONE
            }
            return
        }

        findViewById<View>(R.id.btnRetryMot).visibility = View.GONE
        findViewById<View>(R.id.btnOpenMotBrowser).visibility = View.GONE
        findViewById<View>(R.id.btnOpenMotHistory).visibility = View.VISIBLE

        tvLine.text = history.summaryLine()
        tvStats.text = history.overviewLine()
        tvStats.visibility = View.VISIBLE
        cardMotSummary.visibility = View.VISIBLE

        // The MOT trail supplies the annual mileage the running-cost estimate needs
        updateRunningCost()
    }

    /** Placeholder state for the compact MOT summary while the live fetch is still running. */
    private fun showMotSummaryLoading() {
        findViewById<View>(R.id.cardMotSummary).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvMotSummaryLine).text = "Loading MOT history…"
        findViewById<View>(R.id.tvMotSummaryStats).visibility = View.GONE
        findViewById<View>(R.id.btnOpenMotHistory).visibility = View.GONE
        findViewById<View>(R.id.btnRetryMot).visibility = View.GONE
        findViewById<View>(R.id.btnOpenMotBrowser).visibility = View.GONE
    }

    /** Opens the dedicated MOT history screen for whatever plate is on screen. */
    private fun openMotHistory() {
        val plate = currentReg.ifBlank { etPlate.text.toString() }
        if (plate.isBlank()) return
        startActivity(MotHistoryActivity.intent(this, plate))
    }

    /** Re-runs only the MOT fetch for the plate currently on screen. */
    private fun retryMotFetch() {
        if (currentReg.isEmpty()) return
        val targetReg = currentReg
        findViewById<View>(R.id.btnRetryMot).visibility = View.GONE
        showMotSummaryLoading()
        lifecycleScope.launch {
            val mot = MotHistoryRepository.load(this@MainActivity, targetReg)

            if (currentReg != targetReg) return@launch

            if (MotHistoryScraper.isUsable(mot) && mot != null) {
                currentMot = mot
                bindMotSummary(mot)
                val existingCache = db.cachedVehicleDao().get(targetReg)
                val vehicle = currentVehicle ?: return@launch
                db.cachedVehicleDao().upsert(
                    CachedVehicleEntity.fromData(vehicle, mot, existingCache?.aiReport)
                )
            } else {
                bindMotSummary(mot ?: MotHistoryData(registration = targetReg, errorMessage = "Could not load MOT history — please try again."))
            }
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
