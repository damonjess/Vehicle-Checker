package com.example.vehiclechecker

import androidx.annotation.Keep

// The root response from the API
@Keep
data class MotRecord(
    val registration: String,
    val make: String,
    val model: String,
    val motTests: List<MotTest>?
)

// Individual test details
@Keep
data class MotTest(
    val completedDate: String,
    val testResult: String,
    val odometerValue: String?,
    val odometerUnit: String?,
    val defects: List<MotDefect>?
)

// Specific failures or advisories
@Keep
data class MotDefect(
    val text: String,
    val type: String, // e.g., "Advisory", "Failure", "PRS"
    val dangerous: Boolean
)
