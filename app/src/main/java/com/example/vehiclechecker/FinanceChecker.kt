package com.example.vehiclechecker

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Checks vehicle records for finance and ownership risk indicators.
 *
 * In the UK, DVLA public records do not directly expose active HP/PCP finance contracts.
 * However, V5C logbook issue dates, export markers, and ownership turnover indicators
 * provide crucial risk signals for outstanding finance or logbook loan liabilities.
 */
object FinanceChecker {

    enum class RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    data class FinanceCheckResult(
        val riskLevel: RiskLevel,
        val riskBadgeText: String,
        val statusTitle: String,
        val statusDetail: String,
        val v5cAgeText: String,
        val checklist: List<String>
    )

    fun checkFinanceStatus(vehicle: VehicleData): FinanceCheckResult {
        val lastV5cDate = vehicle.lastV5cIssued
        val daysSinceV5c = calculateDaysSinceV5c(lastV5cDate)
        val isExported = vehicle.exportMarker.contains("Yes", ignoreCase = true)

        val checklist = listOf(
            "1. V5C Logbook Serial Check: Verify the paper V5C issue date matches '$lastV5cDate'.",
            "2. Identity Check: Ensure seller's photo ID matches the name & address on the V5C logbook.",
            "3. Settlement Letter: If previously on HP/PCP finance, request the lender's official clearance letter.",
            "4. Logbook Loans: Ask for proof of purchase or finance settlement receipt.",
            "5. HPI / Register Lookup: Perform a live financial register check to confirm zero outstanding HP/PCP agreements."
        )

        return when {
            isExported -> {
                FinanceCheckResult(
                    riskLevel = RiskLevel.HIGH,
                    riskBadgeText = "HIGH RISK (EXPORTED)",
                    statusTitle = "Export Marker Active",
                    statusDetail = "This vehicle is marked as exported in DVLA records. Cars under active finance cannot legally be exported without lender permission.",
                    v5cAgeText = "V5C Issued: $lastV5cDate",
                    checklist = checklist
                )
            }
            daysSinceV5c != null && daysSinceV5c <= 90 -> {
                val months = daysSinceV5c / 30
                FinanceCheckResult(
                    riskLevel = RiskLevel.HIGH,
                    riskBadgeText = "HIGH RISK (RECENT V5C)",
                    statusTitle = "Recent V5C Logbook Reissue",
                    statusDetail = "The V5C logbook was reissued very recently ($daysSinceV5c days / ~$months month(s) ago). Frequent V5C reissues can indicate recent ownership turnover, logbook loans, or unpaid PCP finance.",
                    v5cAgeText = "Issued $daysSinceV5c days ago ($lastV5cDate)",
                    checklist = checklist
                )
            }
            daysSinceV5c != null && daysSinceV5c <= 180 -> {
                val months = daysSinceV5c / 30
                FinanceCheckResult(
                    riskLevel = RiskLevel.MEDIUM,
                    riskBadgeText = "MEDIUM RISK",
                    statusTitle = "Logbook Issued $months Months Ago",
                    statusDetail = "V5C logbook was issued on $lastV5cDate. Confirm the seller has held the vehicle for this duration and request finance clearance proof.",
                    v5cAgeText = "Issued $months months ago ($lastV5cDate)",
                    checklist = checklist
                )
            }
            lastV5cDate.isNotBlank() -> {
                FinanceCheckResult(
                    riskLevel = RiskLevel.LOW,
                    riskBadgeText = "LOGBOOK STABLE",
                    statusTitle = "Logbook Record Stable",
                    statusDetail = "DVLA V5C logbook date ($lastV5cDate) shows long-term ownership stability. Verify seller ID and clear finance before purchase.",
                    v5cAgeText = "Last V5C Issued: $lastV5cDate",
                    checklist = checklist
                )
            }
            else -> {
                FinanceCheckResult(
                    riskLevel = RiskLevel.MEDIUM,
                    riskBadgeText = "VERIFY FINANCE",
                    statusTitle = "V5C Date Unavailable",
                    statusDetail = "Logbook issue date could not be verified. Ensure you inspect the physical V5C document and perform a finance register check.",
                    v5cAgeText = "V5C Date: Unknown",
                    checklist = checklist
                )
            }
        }
    }

    /** Opens official UK financial register lookup in browser for the registration plate. */
    fun openHpiRegisterCheck(context: Context, registration: String) {
        val cleanReg = registration.replace(" ", "").uppercase().trim()
        val url = if (cleanReg.isNotEmpty()) {
            "https://totalcarcheck.co.uk/FreeCheck?reg=$cleanReg"
        } else {
            "https://www.hpicheck.com/"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hpicheck.com/"))
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
            }
        }
    }

    private fun calculateDaysSinceV5c(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val formats = listOf(
            SimpleDateFormat("d MMMM yyyy", Locale.UK),
            SimpleDateFormat("dd/MM/yyyy", Locale.UK),
            SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        )
        val now = Date()
        for (format in formats) {
            try {
                val date = format.parse(dateStr)
                if (date != null) {
                    val diffMs = now.time - date.time
                    return TimeUnit.MILLISECONDS.toDays(diffMs)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
