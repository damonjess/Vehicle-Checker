package com.example.vehiclechecker

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The dedicated MOT history screen: the vehicle's tests, pass-rate insights, mileage trail and
 * safety recalls, all in one place of their own instead of buried in the main report.
 *
 * It is opened straight after a plate scan and from the compact MOT summary on the report.
 * The record is shown instantly from the offline cache when one exists, then refreshed live
 * unless that cached copy is still fresh.
 */
class MotHistoryActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private var reg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mot_history)

        reg = intent.getStringExtra(EXTRA_PLATE)?.replace(" ", "")?.uppercase().orEmpty()

        findViewById<TextView>(R.id.tvMotScreenPlate).text = reg.ifBlank { "No registration" }
        findViewById<View>(R.id.btnMotBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRetryMot).setOnClickListener { refresh(fromRetry = true) }
        findViewById<View>(R.id.btnOpenMotBrowser).setOnClickListener { openOnOfficialSite() }

        if (reg.isEmpty()) {
            showError("No registration number was supplied.")
            return
        }
        refresh()
    }

    /**
     * Offline cache first — it renders instantly, then a live fetch replaces it unless the
     * cached copy is under 24 hours old (matching the report's freshness policy).
     */
    private fun refresh(fromRetry: Boolean = false) {
        val contentVisible = findViewById<View>(R.id.motContent).visibility == View.VISIBLE
        if (fromRetry || !contentVisible) {
            findViewById<View>(R.id.motProgress).visibility = View.VISIBLE
            findViewById<View>(R.id.tvMotStatus).visibility = View.GONE
        }
        findViewById<View>(R.id.motActions).visibility = View.GONE

        lifecycleScope.launch {
            val cached = db.cachedVehicleDao().get(reg)
            val cachedMot = cached?.toMot()?.takeIf { MotHistoryScraper.isUsable(it) }
            if (cachedMot != null) render(cachedMot)

            val isFresh = cached != null && cachedMot != null &&
                System.currentTimeMillis() - cached.timestamp < CACHE_FRESH_MS
            if (isFresh) {
                Log.d(TAG, "Cached MOT history for $reg is under 24h old — skipping the live fetch")
                return@launch
            }

            val live = MotHistoryRepository.load(this@MotHistoryActivity, reg)
            if (MotHistoryScraper.isUsable(live) && live != null) {
                render(live)
                persist(cached, live)
            } else if (cachedMot == null) {
                showError(live?.errorMessage ?: "Could not load MOT history — please try again.")
            }
            // Otherwise the cached copy stays on screen rather than being replaced by an error.
        }
    }

    /** Keeps the offline cache in step with what was just fetched here. */
    private suspend fun persist(cached: CachedVehicleEntity?, mot: MotHistoryData) {
        // Without an existing row there is no vehicle data to attach the MOT record to; the
        // main report owns that row, so it will save this plate's MOT itself.
        if (cached == null) return
        try {
            db.cachedVehicleDao().upsert(
                CachedVehicleEntity.fromData(cached.toVehicle(), mot, cached.aiReport)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the cached MOT history for $reg", e)
        }
    }

    private fun showError(message: String) {
        findViewById<View>(R.id.motProgress).visibility = View.GONE
        findViewById<View>(R.id.motContent).visibility = View.GONE
        findViewById<TextView>(R.id.tvMotStatus).apply {
            text = message
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.motActions).visibility = View.VISIBLE
    }

    private fun openOnOfficialSite() {
        if (reg.isEmpty()) return
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("$MOT_RESULTS_URL?registration=$reg&checkRecalls=true")
                )
            )
        } catch (_: Exception) {
            // No browser available — nothing useful to do.
        }
    }

    private fun render(history: MotHistoryData) {
        findViewById<View>(R.id.motProgress).visibility = View.GONE
        findViewById<View>(R.id.tvMotStatus).visibility = View.GONE
        findViewById<View>(R.id.motActions).visibility = View.GONE

        findViewById<TextView>(R.id.tvMotHistorySummary).text = history.summaryLine()

        // Colour, fuel and registration date exactly as the MOT record reports them
        val detailsTv = findViewById<TextView>(R.id.tvMotVehicleDetails)
        val detailBits = mutableListOf<String>()
        if (history.colour.isNotBlank()) detailBits += history.colour.lowercase().replaceFirstChar { it.uppercase() }
        if (history.fuelType.isNotBlank()) detailBits += history.fuelType.lowercase().replaceFirstChar { it.uppercase() }
        if (history.dateRegistered.isNotBlank()) detailBits += "registered ${history.dateRegistered}"
        detailsTv.text = detailBits.joinToString(" · ")
        detailsTv.visibility = if (detailBits.isEmpty()) View.GONE else View.VISIBLE

        // Where the history starts and what it adds up to
        val rangeTv = findViewById<TextView>(R.id.tvMotHistoryRange)
        val rangeBits = mutableListOf<String>()
        history.firstTestDate?.let { rangeBits += "Records from $it" }
        rangeBits += "${history.passCount} clean pass" + if (history.passCount == 1) "" else "es"
        if (history.totalAdvisoryCount > 0) rangeBits += "${history.totalAdvisoryCount} advisories"
        if (history.totalFailureCount > 0) rangeBits += "${history.totalFailureCount} defects"
        rangeTv.text = rangeBits.joinToString(" · ")
        rangeTv.visibility = View.VISIBLE

        findViewById<View>(R.id.motContent).visibility = View.VISIBLE

        bindInsights(history)
        bindComponentHealth(history)
        bindTests(history)
        bindMileageTable(history)
        bindRecurringIssues(history)
        bindRecalls(history)
    }

    private fun bindInsights(history: MotHistoryData) {
        findViewById<TextView>(R.id.tvGapYears).text = history.gapYears.toString()

        // Live countdown to the next test, so the expiry date means something
        val countdownTv = findViewById<TextView>(R.id.tvMotExpiryCountdown)
        val daysLeft = history.daysUntilMotExpiry()
        countdownTv.text = when {
            daysLeft == null -> "Not recorded"
            daysLeft < 0 -> "Expired ${-daysLeft} day" + if (daysLeft == -1) " ago" else "s ago"
            daysLeft == 0 -> "Today"
            else -> "in $daysLeft days"
        }
        countdownTv.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    daysLeft == null -> R.color.white
                    daysLeft < 0 -> R.color.status_danger
                    daysLeft <= 30 -> R.color.status_warn
                    else -> R.color.status_success
                }
            )
        )

        val passRateTv = findViewById<TextView>(R.id.tvPassRate)

        val trendText = when (history.conditionTrend) {
            ConditionTrend.IMPROVING -> "  📈 Improving"
            ConditionTrend.DEGRADING -> "  📉 Degrading"
            ConditionTrend.STABLE -> "  ➖ Stable"
            ConditionTrend.INSUFFICIENT_DATA -> ""
        }

        passRateTv.text = "${history.passRatePercent}%$trendText"

        when (history.conditionTrend) {
            ConditionTrend.IMPROVING -> passRateTv.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            ConditionTrend.DEGRADING -> passRateTv.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
            else -> passRateTv.setTextColor(ContextCompat.getColor(this, R.color.white))
        }

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

    /** Faults and advisories that show up on more than one test — the repeat offenders. */
    private fun bindRecurringIssues(history: MotHistoryData) {
        val panel = findViewById<View>(R.id.recurringPanel)
        val container = findViewById<LinearLayout>(R.id.recurringContainer)
        container.removeAllViews()

        val issues = history.recurringIssues.take(6)
        if (issues.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE

        issues.forEach { issue ->
            val accent = ContextCompat.getColor(
                this,
                if (issue.isFailure) R.color.status_danger else R.color.status_warn
            )
            container.addView(
                TextView(this).apply {
                    text = "• ${issue.text}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    setPadding(0, 8, 0, 0)
                }
            )
            container.addView(
                TextView(this).apply {
                    val kind = if (issue.isFailure) "failure" else "advisory"
                    val years = issue.years.joinToString(", ")
                    text = "Flagged ${issue.occurrences} times as a $kind" +
                        if (years.isBlank()) "" else " — $years"
                    textSize = 12f
                    setTextColor(accent)
                    setTypeface(null, Typeface.BOLD_ITALIC)
                    setPadding(14, 2, 0, 0)
                }
            )
        }
    }

    private fun bindComponentHealth(history: MotHistoryData) {
        val card = findViewById<View>(R.id.cardComponentHealth)
        val tvOverallBadge = findViewById<TextView>(R.id.tvOverallHealthBadge)
        val container = findViewById<LinearLayout>(R.id.componentHealthContainer)

        card.visibility = View.VISIBLE
        container.removeAllViews()

        val report = history.componentHealth
        tvOverallBadge.text = "${report.overallScore}%"

        val overallColor = when (report.overallStatus) {
            ComponentHealthCalculator.ComponentStatus.GREEN -> ContextCompat.getColor(this, R.color.status_success)
            ComponentHealthCalculator.ComponentStatus.AMBER -> ContextCompat.getColor(this, R.color.status_warn)
            ComponentHealthCalculator.ComponentStatus.RED -> ContextCompat.getColor(this, R.color.status_danger)
        }
        tvOverallBadge.setTextColor(overallColor)

        report.components.forEach { comp ->
            val row = layoutInflater.inflate(R.layout.view_component_health_row, container, false)

            val tvDomain = row.findViewById<TextView>(R.id.tvComponentDomainName)
            val tvScore = row.findViewById<TextView>(R.id.tvComponentScore)
            val pbScore = row.findViewById<ProgressBar>(R.id.pbComponentScore)
            val tvNote = row.findViewById<TextView>(R.id.tvComponentNote)

            tvDomain.text = comp.domain.displayName
            tvScore.text = "${comp.score}%"
            pbScore.progress = comp.score

            val compColor = when (comp.status) {
                ComponentHealthCalculator.ComponentStatus.GREEN -> ContextCompat.getColor(this, R.color.status_success)
                ComponentHealthCalculator.ComponentStatus.AMBER -> ContextCompat.getColor(this, R.color.status_warn)
                ComponentHealthCalculator.ComponentStatus.RED -> ContextCompat.getColor(this, R.color.status_danger)
            }
            tvScore.setTextColor(compColor)
            pbScore.progressTintList = ColorStateList.valueOf(compColor)

            tvNote.text = comp.latestNote

            container.addView(row)
        }
    }

    private fun bindRecalls(history: MotHistoryData) {
        val cardRecalls = findViewById<View>(R.id.cardRecalls)
        val tvTitle = findViewById<TextView>(R.id.tvRecallTitle)
        val tvDetail = findViewById<TextView>(R.id.tvRecallDetail)
        val btnDvsa = findViewById<Button>(R.id.btnCheckDvsaRecalls)

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

        btnDvsa.setOnClickListener {
            val url = "https://www.check-vehicle-recalls.service.gov.uk/recall-type"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    private fun bindTests(history: MotHistoryData) {
        val testsContainer = findViewById<LinearLayout>(R.id.motTestsContainer)
        testsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        // Every recorded test, grouped under its year heading so the history from
        // different years is easy to scan.
        var lastYearHeader: Int? = null
        var isFirstYearHeader = true

        history.tests.forEachIndexed { index, test ->
            val previousTest = history.tests.getOrNull(index + 1)

            // Year group heading (e.g. "2026") before the first test of that year
            val testYear = test.yearTested
            if (testYear != null && testYear != lastYearHeader) {
                lastYearHeader = testYear
                val topPadding = if (isFirstYearHeader) 0 else (14 * density).toInt()
                isFirstYearHeader = false
                val yearHeader = TextView(this).apply {
                    text = testYear.toString()
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, topPadding, 0, 0)
                }
                testsContainer.addView(yearHeader)
            }

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

            // At-a-glance line so a collapsed row still carries its numbers
            val metaBits = mutableListOf<String>()
            if (test.mileage.isNotBlank()) metaBits += test.mileage
            if (test.advisories.isNotEmpty()) {
                val n = test.advisories.size
                metaBits += "$n advisor" + if (n == 1) "y" else "ies"
            }
            if (test.failures.isNotEmpty()) {
                val n = test.failures.size
                metaBits += "$n defect" + if (n == 1) "" else "s"
            }
            if (test.expiryDate.isNotBlank()) metaBits += "expires ${test.expiryDate}"
            row.findViewById<TextView>(R.id.tvTestMeta).text = metaBits.joinToString(" · ")

            // Detail lines
            bindDetailLine(row, R.id.detailDate, "Date of Test:", test.dateTested)
            bindDetailLine(row, R.id.detailExpiry, "Expiry Date:", test.expiryDate.ifBlank { "Not recorded" })
            bindDetailLine(row, R.id.detailOdometer, "Odometer:", test.mileage.ifBlank { "Not recorded" })
            bindDetailLine(row, R.id.detailDifference, "Difference:", test.mileageDifferenceText ?: "First record")
            bindDetailLine(row, R.id.detailTestNumber, "Test Number:", test.testNumber.ifBlank { "Not recorded" })

            // Reasons the test failed — parsed from the page and shown here, not just counted
            val failuresSection = row.findViewById<View>(R.id.failuresSection)
            val failuresContainer = row.findViewById<LinearLayout>(R.id.failuresContainer)
            if (test.failures.isEmpty()) {
                failuresSection.visibility = View.GONE
            } else {
                failuresSection.visibility = View.VISIBLE
                failuresContainer.removeAllViews()
                test.failures.forEach { failure ->
                    failuresContainer.addView(
                        TextView(this).apply {
                            text = "• $failure"
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                    )
                }
            }

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

                    // Flag an advisory that the same vehicle was already told about last year
                    val normalize = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
                    val currentNorm = normalize(advisory)

                    val repeated = previousTest?.advisories?.any { pastAdv ->
                        val pastNorm = normalize(pastAdv)
                        currentNorm.isNotEmpty() && (currentNorm.contains(pastNorm) || pastNorm.contains(currentNorm))
                    } == true

                    if (repeated) {
                        val pastYear = previousTest?.yearTested ?: "a previous test"
                        advisoriesContainer.addView(
                            TextView(this).apply {
                                text = "⚠️ Repeated Advisory: This issue was also flagged in $pastYear and has been left unrepaired."
                                textSize = 12f
                                setPadding(30, 4, 0, 12)
                                setTextColor(ContextCompat.getColor(context, R.color.status_warn))
                                setTypeface(null, Typeface.BOLD_ITALIC)
                            }
                        )
                    }
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
                    setTextColor(ContextCompat.getColor(this@MotHistoryActivity, R.color.white))
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
        // tests are newest-first: the previous odometer reading is the NEXT entry.
        // forEachIndexed (not indexOf) so duplicate test rows can't corrupt the pairing.
        history.tests.forEachIndexed { index, test ->
            val year = test.yearTested ?: return@forEachIndexed
            val miles = test.mileageMiles?.toFloat() ?: return@forEachIndexed
            val prevMiles = history.tests.getOrNull(index + 1)?.mileageMiles?.toFloat()
            val driven = if (prevMiles != null) (miles - prevMiles).coerceAtLeast(0f) else 0f
            byYear[year] = (byYear[year] ?: 0f) + driven
        }
        chart.setEntries(byYear.entries.map { MileageBarChart.Entry(it.key, it.value) }.reversed())

        val rowsContainer = findViewById<LinearLayout>(R.id.mileageRowsContainer)
        rowsContainer.removeAllViews()

        val lastMileage = history.lastMileageMiles
        findViewById<TextView>(R.id.tvLastMileage).text =
            lastMileage?.let { String.format(Locale.UK, "%,d miles", it) } ?: "Not recorded"
        findViewById<TextView>(R.id.tvAvgMileage).text =
            history.averageMilesPerYear?.let { String.format(Locale.UK, "%,d miles", it) }
                ?: "Not recorded"

        history.tests.forEach { test ->
            if (test.mileageMiles == null) return@forEach
            val row = layoutInflater.inflate(R.layout.view_mileage_row, rowsContainer, false)
            row.findViewById<TextView>(R.id.tvMileageDate).text = test.dateTested
            row.findViewById<TextView>(R.id.tvMileageOdometer).text = test.mileage

            val tvDiff = row.findViewById<TextView>(R.id.tvMileageDiff)
            tvDiff.text = test.mileageDifferenceText ?: "--"
            tvDiff.setTextColor(
                when {
                    test.mileageDifference == null -> 0xFFB0BEC5.toInt()
                    test.mileageDifference!! < 0 -> 0xFFFF5252.toInt()
                    else -> 0xFF69F0AE.toInt()
                }
            )
            rowsContainer.addView(row)
        }
    }

    companion object {
        private const val TAG = "MotHistoryActivity"
        private const val EXTRA_PLATE = "extra_plate"
        private const val CACHE_FRESH_MS = 24L * 60 * 60 * 1000L
        private const val MOT_RESULTS_URL = "https://www.check-mot.service.gov.uk/results"

        /** Entry point used by the report and by the plate-scan flow. */
        fun intent(context: Context, plate: String): Intent =
            Intent(context, MotHistoryActivity::class.java).putExtra(EXTRA_PLATE, plate)
    }
}
