package com.example.vehiclechecker

import androidx.annotation.Keep

// The root response from the API
@Keep
data class MotRecord(
    val registration: String? = null,
    val make: String? = null,
    val model: String? = null,
    val fuelType: String? = null,
    val primaryColour: String? = null,
    val firstUsedDate: String? = null,
    val hasOutstandingRecall: String? = null,
    val motTests: List<MotTest>? = null
)

// Individual test details
@Keep
data class MotTest(
    val completedDate: String? = null,
    val testResult: String? = null,
    val expiryDate: String? = null,
    val odometerValue: String? = null,
    val odometerUnit: String? = null,
    val odometerResultType: String? = null,
    val motTestNumber: String? = null,
    val defects: List<MotDefect>? = null
)

// Specific failures or advisories
@Keep
data class MotDefect(
    val text: String? = null,
    val type: String? = null, // e.g., "Advisory", "Failure", "PRS", "ADVISORY"
    val dangerous: Boolean = false
)
