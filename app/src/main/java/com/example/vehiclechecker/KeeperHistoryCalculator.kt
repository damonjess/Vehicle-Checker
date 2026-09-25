package com.example.vehiclechecker

/**
 * Calculates ownership duration, keeper timeline, and retention statistics
 * from free DVLA public V5C logbook and registration records.
 */
object KeeperHistoryCalculator {

    data class KeeperHistoryResult(
        val firstRegisteredDate: String,
        val totalVehicleAgeText: String,
        val currentV5cDate: String,
        val currentKeeperDurationText: String,
        val ownershipRetentionPercent: Int?,
        val stabilityBadgeText: String,
        val stabilityNote: String
    )

    fun calculate(vehicle: VehicleData): KeeperHistoryResult {
        val firstRegDateStr = vehicle.firstRegistered.ifBlank { vehicle.yearOfManufacture }
        val lastV5cDateStr = vehicle.lastV5cIssued

        val registeredEpoch = DateUtils.parseFlexible(firstRegDateStr)
        val v5cEpoch = DateUtils.parseFlexible(lastV5cDateStr)

        val totalVehicleDays = if (registeredEpoch != null) {
            (-DateUtils.daysUntil(registeredEpoch)).coerceAtLeast(1)
        } else null

        val currentKeeperDays = if (v5cEpoch != null) {
            (-DateUtils.daysUntil(v5cEpoch)).coerceAtLeast(0)
        } else null

        val totalAgeText = if (totalVehicleDays != null) {
            val years = totalVehicleDays / 365
            val months = (totalVehicleDays % 365) / 30
            "$years yrs, $months mos"
        } else {
            "Age unavailable"
        }

        val keeperDurationText = if (currentKeeperDays != null) {
            val years = currentKeeperDays / 365
            val months = (currentKeeperDays % 365) / 30
            if (years > 0) "$years yrs, $months mos" else "$months mos"
        } else {
            "Duration unavailable"
        }

        val retentionPercent = if (totalVehicleDays != null && currentKeeperDays != null && totalVehicleDays > 0) {
            ((currentKeeperDays.toDouble() / totalVehicleDays.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else null

        val (badgeText, noteText) = when {
            currentKeeperDays == null -> {
                "UNKNOWN" to "V5C issue date is missing from DVLA records. Verify the physical V5C document when viewing the vehicle."
            }
            currentKeeperDays < 180 -> {
                val months = (currentKeeperDays / 30).coerceAtLeast(1)
                "RECENT KEEPER CHANGE" to "The current keeper has held the vehicle for only ~$months month(s) (issued $lastV5cDateStr). Ask the seller the reason for short-term sale."
            }
            currentKeeperDays < 365 -> {
                "CURRENT KEEPER < 1 YEAR" to "Current keeper acquired this vehicle on $lastV5cDateStr (~${currentKeeperDays / 30} months ago)."
            }
            retentionPercent != null && retentionPercent >= 50 -> {
                "LONG-TERM KEEPER" to "The current keeper has owned this vehicle for $retentionPercent% of its total lifetime ($keeperDurationText). This indicates high ownership stability."
            }
            else -> {
                "STABLE KEEPER HISTORY" to "Current keeper has held this vehicle for $keeperDurationText (since $lastV5cDateStr)."
            }
        }

        return KeeperHistoryResult(
            firstRegisteredDate = firstRegDateStr.ifBlank { "Unknown" },
            totalVehicleAgeText = totalAgeText,
            currentV5cDate = lastV5cDateStr.ifBlank { "Unknown" },
            currentKeeperDurationText = keeperDurationText,
            ownershipRetentionPercent = retentionPercent,
            stabilityBadgeText = badgeText,
            stabilityNote = noteText
        )
    }
}
